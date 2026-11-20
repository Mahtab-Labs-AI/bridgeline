# Plan 2a: Real USB Sharing

> **For agentic workers:** implement task by task with TDD where logic is testable on the JVM. Commit after each task. Spec: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` §3, §5, §6, §7 and **§8a**. Also read the README "Handoff notes for Plan 2". They are binding contracts for the Android layer.

**Goal:** The Pixel really shares its cellular data with a laptop over USB:
- `adb forward` plus the app's loopback `ProxyServer`;
- a foreground service;
- a cellular-bound `Egress`.

The mockup UI drives it through a real `SessionRepository`, and a demo mode keeps the fake. Wi-Fi Direct is Plan 2b.

**Branch:** `feature/usb-sharing` (off `feature/ui-mockup`). **Toolchain:** as in the UI-mockup plan: AGP 8.13.2, compileSdk/targetSdk 36, minSdk 29, SDK at `E:\Source\Android\sdk`, `. .\scripts\env.ps1`. Commit trailer `Co-Authored-By: Claude Opus 5.5 (1M context)`.

## Tasks

### Task 1: Pinned slots in `ClientRegistry` (`:proxy-core`, TDD)

Add `fun addPinnedSlot(name: String, ip: InetAddress): Slot?`:
- it creates a slot bound to `ip` and marked pinned;
- it counts toward `maxClients` and returns null when full;
- it returns null if another pinned slot already holds `ip`.

`Slot` gains `val pinned: Boolean` (default false), and `SlotRecord` gains it too. Pinned slots are not meant to be persisted, so `snapshot()` excludes them.

Rules:
- `clearBindings()` and `unbind(ip)` never touch pinned slots.
- `onPacFetch(token, ip)`: if `ip` is held by a pinned slot, return null and change nothing, whatever the token.
- Fetching a pinned slot's own token can't move it either: pinned slots never rebind.
- `ProxyServer.revoke(pinnedSlotId)` works as for any slot.

Tests for each rule. The full `:proxy-core` suite must stay green (131 tests, plus the new ones).

### Task 2: `CellularEgress` (`:app`, `com.wifiproxy.app.egress`)

This implements `com.wifiproxy.core.egress.Egress`. It is one long-lived object that swaps its `Network` internally.

**Network tracking**
- Call `ConnectivityManager.requestNetwork(NetworkRequest(TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET), callback)` while active, from `start()`. `stop()` unregisters it.
- Track `network: StateFlow<Network?>`, `isAvailable` (network != null) and `isValidated` (`NET_CAPABILITY_VALIDATED` from `onCapabilitiesChanged`).
- On `onLost`, set the network to null.

**`resolve(host)`**
- Use `DnsResolver.getInstance().query(network, host, FLAG_EMPTY, executor, cancellationSignal, callback)` for both families.
- Wrap it in `suspendCancellableCoroutine`, cancelling the `CancellationSignal` on coroutine cancel.
- Apply a 5 s timeout via `withTimeoutOrNull`. On timeout throw `SocketTimeoutException`, never `TimeoutCancellationException`.
- With no network, throw `EgressUnavailableException`. If both families return nothing, throw `UnknownHostException`.
- IP literals short-circuit with no query.
- Add a small TTL cache: honour the answer TTL, falling back to 60 s, and key it by network plus host.

**`newChannel()`**
- `SocketChannel.open()`, then `network.bindSocket(channel.socket())`.
- On any failure, close the channel and throw: `EgressUnavailableException` if the network is gone, otherwise an `IOException`.
- Never return an unbound channel.
- It's non-suspending inside, so cancellation can't leak a channel.

**Host addresses**
- `hostAddresses(): List<Cidr>` collects `LinkProperties.getAllLinkAddresses()` for **every** network (`ConnectivityManager.allNetworks`) as /32 and /128.
- Add a callback hook `onLinkChange: () -> Unit` that fires from `onLinkPropertiesChanged`.

**Testing**
- Wrap the Android APIs behind tiny internal seams, e.g. a `DnsQuery` function type, so the timeout, IOException mapping and cache can be unit-tested on the JVM with fakes.
- Device behaviour is verified in Task 6.

### Task 3: Session controller + foreground service (`:app`, `com.wifiproxy.app.service`)

**`UsbSessionController`**
- A plain class owning the session state machine for the USB path:
  `Idle → Starting → Running ⇄ Degraded(NoEgress) → Stopping → Idle`
- It exposes `state: StateFlow<SessionPhase>` and `degradedReason`.
- `start()`:
  1. start `CellularEgress`;
  2. create the shared `ClientRegistry(maxClients from settings)`;
  3. call `addPinnedSlot("Laptop (USB)", 127.0.0.1)`;
  4. build a `ProxyServer(ProxyConfig(bindAddress = 127.0.0.1, port = 8081, peerSubnet = 127.0.0.0/8), registry, egress, DestinationFilter.default(Cidr.parse("192.168.49.0/24"), egress.hostAddresses()), onError = log)` and `start()` it.
- On egress loss/regain, switch Running ⇄ Degraded.
- On a link change, call `server.updateFilter(...)` with fresh host addresses.
- A watchdog every 30 s: if `!server.isAccepting`, rebuild the server.
- `stop()` runs off the main thread: the server stops, then the egress stops.
- Log entries go to a `StateFlow<List<LogEntry>>`, holding the last 500. **Never log URLs or hostnames.**

**`ProxySessionService`**
- `foregroundServiceType="connectedDevice"`. Declare these permissions:
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
  - `CHANGE_NETWORK_STATE`, `ACCESS_NETWORK_STATE`;
  - `INTERNET`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- Verify the connectedDevice prerequisites on API 34–36. If CHANGE_NETWORK_STATE alone doesn't satisfy them, report it rather than guessing; `specialUse` is the documented fallback.
- Call `startForeground()` immediately with an ongoing notification: "Sharing over USB", up/down totals, and a **Stop** action.
- Hold a partial wake lock while Running. Use `START_NOT_STICKY` for 2a; restart-on-death is Plan 2b.
- The controller lives in the service. The UI binds through a small singleton `SessionHolder` exposing the controller's flows, so the activity can observe without AIDL.

### Task 4: Real repository + UI wiring (`:app`)

**`ServiceSessionRepository : SessionRepository`**
- `start()` starts the foreground service; `stop()` sends the stop action.
- It maps the controller state and polls `registry.slots()` every 1 s for counters.
- `revoke(id)` goes through `ProxyServer.revoke`.
- `addDevice`/`inviteFor` return null for now (Wi-Fi is Plan 2b).
- `settings` and `checks` are in-memory. Setup checks are real for:
  - notification permission;
  - cellular available;
  - USB debugging enabled (`Settings.Global.ADB_ENABLED`);
  - the battery-optimisation exemption (`PowerManager.isIgnoringBatteryOptimizations`). Its Fix-it opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.

**Settings changes**
- Add a **"USB sharing (laptop)"** switch, on by default. In 2a the session is USB-only, so the switch is informational and disabled.
- Add a **"Demo mode"** switch: on = `FakeSessionRepository`, off = `ServiceSessionRepository`. It's off by default on a real device.
- Persist both in SharedPreferences.

**UI changes**
- Request `POST_NOTIFICATIONS` at the first Start.
- The Devices tab shows the "Laptop (USB)" slot card with the connect steps:
  1. enable USB debugging;
  2. plug in;
  3. on the laptop run `connect-usb.ps1`.
  Show copyable commands. While real (non-demo) mode is on, the "Add device" button shows "Wi-Fi devices arrive in the next version".
- Home shows real live counters.

### Task 5: Laptop script `tools/usb-client/connect-usb.ps1` (Windows)

**Parameters:** `-AdbPath` (default: search `$env:ANDROID_HOME`/`platform-tools`, `E:\Source\Android\sdk\platform-tools`, then PATH), `-Port 8081`, `-Serial` (optional).

**Steps**
1. Check that exactly one `device` is attached, or use `-Serial`. Run `adb forward tcp:$Port tcp:$Port`.
2. Probe `http://127.0.0.1:$Port/` through the forward and expect any HTTP response, such as 404, proving the app is listening. If nothing answers, print "Start sharing in the app first".
3. Save the current `HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings` values `ProxyEnable`, `ProxyServer`, `ProxyOverride`, `AutoConfigURL`.
4. Set `ProxyEnable=1`, `ProxyServer=127.0.0.1:$Port`, `ProxyOverride=<local>`, remove `AutoConfigURL`, and notify WinINet through `InternetSetOption` via P/Invoke so browsers pick it up without restarting.
5. Print the status and the WinHTTP hint, then wait for Enter.
6. In a `finally` block (so Ctrl+C works too), restore the saved values exactly, notify again, and run `adb forward --remove tcp:$Port`.

Also add `tools/usb-client/README.md` covering usage and macOS/Linux manual steps.

**Testing**
- Pester is not required.
- Add `-DryRun`, which prints the actions without changing anything, and run it.
- Do the real run in Task 6.

### Task 6: End-to-end on the emulator, then the Pixel

1. Install the debug APK on the emulator. Turn off Demo mode and press Start. The emulator's mobile data acts as cellular.
2. On this PC:
   - `adb -s emulator-5554 forward tcp:8081 tcp:8081`;
   - `curl -x http://127.0.0.1:8081 -sS -o NUL -w "%{http_code}" https://example.com` → `200`;
   - `curl -x http://127.0.0.1:8081 http://example.com` → 200.
   - CONNECT to `127.0.0.1:22` through the proxy → `403`, from the filter.
   - Check the counters move on Home.
3. Run `connect-usb.ps1 -Serial emulator-5554` and confirm a browser on this PC loads a page through it. Then restore and remove the forward.
4. When the user's Pixel is connected (`adb devices`), repeat with `-Serial <pixel>` and hand over to the user.

### Task 7: Laptop kit (offline laptops; spec §8a "Laptop kit")

**Script changes**
- In `connect-usb.ps1`, the `-AdbPath` search also checks `$PSScriptRoot\platform-tools\adb.exe` **first**.

**Build: bundle the scripts**
- A Gradle `Copy` task in `:app` puts `tools/usb-client/connect-usb.ps1` into `app/src/main/assets/laptop-kit/`. Run it before `preBuild`, into a generated assets dir, so the source of truth stays in `tools/`.
- Also add the files `connect-usb.cmd` (CRLF) and `README.txt` there. README.txt covers the steps: plug in, File transfer, copy the folder, double-click `connect-usb.cmd`, then the driver note.

**`LaptopKitBuilder` (`com.wifiproxy.app.kit`)**
- `suspend fun prepare(progress: (KitProgress) -> Unit): KitResult`.
- Fetch `repository2-3.xml`, falling back to `repository2-1.xml`. Parse it with `XmlPullParser` and pick the `remotePackage path="platform-tools"` on the stable channel whose `archive` has `host-os` `windows`. Read the `complete/url` (relative to `https://dl.google.com/android/repository/`), the `size` and the `checksum` (sha1).
- Download with `HttpURLConnection` over the default network, with timeouts and progress. Verify the size and SHA-1, and **refuse on mismatch**.
- Extract with `ZipInputStream`. Reject entries with `..` or absolute paths (zip-slip).
- Write every file through `MediaStore.Downloads` with `RELATIVE_PATH = "Download/WiFiProxy-LaptopKit/<subdir>"` and `IS_PENDING` set then cleared.
- Re-preparing replaces the previous kit: delete our own previous MediaStore entries first, tracking their IDs in prefs.
- Add the three asset files.
- Unit-test the pure parts on the JVM: index parsing (use a trimmed real-shape XML fixture), checksum verification and the zip-slip guard.

**UI**
- On Devices → the "Laptop (USB)" card, add a **Prepare laptop kit** button.
- States: not prepared / downloading N% / verifying / ready ("Download/WiFiProxy-LaptopKit, copy it to the laptop over USB File transfer") / failed (with the reason).
- Works in both demo and real mode, since it's real either way.

**Verify on the emulator**
- Prepare the kit.
- `adb shell ls /sdcard/Download/WiFiProxy-LaptopKit/` shows `platform-tools/adb.exe`, `connect-usb.ps1`, `connect-usb.cmd` and `README.txt`.
- `adb pull` the folder to the scratchpad, then run `connect-usb.cmd -DryRun`. Passing args through the cmd is fine; add `%*` forwarding. It must find the bundled `adb.exe`.

## Out of scope (2a)
Wi-Fi Direct, slot persistence, restart-on-death, OEM guide, thermal, `ACCESS_LOCAL_NETWORK` (target 37).
