# Android Wi-Fi Proxy — Design Spec

- **Date:** 2026-11-19
- **Status:** Approved design, pending implementation plan
- **Inputs:** 3-engineer design panel (network transport, platform/lifecycle, client UX & security)

## 1. Goal and constraints

Share a host Android phone's **cellular** data with up to **3** other phones over Wi-Fi, without using Android's system tethering/hotspot (the carrier blocks or meters tethering). Traffic must leave the host as host-originated connections.

| Constraint | Decision |
|---|---|
| Client devices | Any phone (Android or iPhone). **No app install** — client only joins Wi-Fi and sets a proxy (PAC URL or `.mobileconfig`). |
| Host | **Non-root, sideload only** (not Play Store). Public APIs only; no hidden APIs required. |
| Platform | Kotlin, single process. `minSdk 29`, `targetSdk`/`compileSdk 37`. |
| Clients | 1–3 concurrent, configurable in the app (default 1). |
| Access | Wi-Fi password **predefined by the host user**; no default ships. |

**Non-goals (v1):** SOCKS5, LocalOnlyHotspot fallback, proxy authentication, UDP of any kind, HTTP caching, Play Store distribution.

**Planned v2 (out of scope here):** optional Android client app (`VpnService` + tun2socks) routing all client traffic to a SOCKS5 listener added to the host. Restores UDP, calls, games and proxy-ignoring apps for Android clients only.

## 2. Key decisions and rationale

1. **Link = Wi-Fi Direct autonomous group owner** via `WifiP2pManager.createGroup(channel, WifiP2pConfig, …)` with `WifiP2pConfig.Builder` (API 29+): `setNetworkName("DIRECT-xy-<suffix>")`, `setPassphrase(<user password>)`, `setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` with fallback to `AUTO`. Legacy clients (iPhone, Android) join it as an ordinary WPA2-PSK network.
   - *Rejected: LocalOnlyHotspot* — SSID/passphrase are random each start; setting them requires a `@SystemApi`, which conflicts with the predefined-password requirement.
   - Neither P2P nor LOHS invokes the carrier tethering entitlement check (that runs only on `TetheringManager.startTethering`).
2. **Proxy = pure Kotlin HTTP proxy** (CONNECT + absolute-URI forwarding) on coroutines over `java.nio`. Not Ktor server (its HTTP pipeline obstructs CONNECT tunnelling); not gomobile/native (would need a JNI callback for `Network.bindSocket`).
3. **Egress = explicit cellular binding.** Every upstream socket and every DNS lookup is bound to a cellular `Network`. Rule: **bound-or-refuse** — never fall back to the default network.
4. **Client config = PAC, not manual proxy.** Android's NetworkMonitor treats a successful PAC fetch as network validation (removes "No internet"); Apple documents APNs proxying only via PAC (iOS 13.4+).
5. **Two-layer access control:** Wi-Fi password gates joining the network; per-device slot tokens gate proxy use and enforce the 1–3 cap.
6. **No proxy auth** — Android's Wi-Fi proxy setting cannot carry credentials and many apps mishandle `407`.

## 3. Architecture

Single-process app. A `connectedDevice` foreground service (`ProxySessionService`) hosts all session components.

| Unit | Responsibility | Interface (sketch) |
|---|---|---|
| `SessionController` | Single-owner coroutine actor; runs the session state machine | `start()`, `stop()`, `state: StateFlow<SessionState>` |
| `P2pGroupLink` (implements `LinkManager`) | Create/maintain the Wi-Fi Direct group; read back and verify actual SSID/passphrase; recreate on teardown | `start(cfg): Flow<LinkState>`, `stop()` |
| `EgressNetworkProvider` | Hold a cellular `requestNetwork`; provide bound sockets and DNS | `network: StateFlow<Network?>`, `connect(host, port)`, `resolve(host)` |
| `ProxyServer` (`:proxy-core`) | Listener on group-owner IP; HTTP proxy, PAC, `.mobileconfig`, connectivity-probe answers | `ProxyServer(config, registry, egress, filter, onError)`, `start()`, `stop()`, `revoke(slotId)`, `updateFilter(f)`, `isAccepting`, `localPort`; stats by polling `registry.slots()` |
| `ClientRegistry` (`:proxy-core`) | Client slots, tokens, token→IP binding, cap enforcement | `addSlot(name): Slot?`, `onPacFetch(token, ip)`, `admittedSlot(ip): Slot?`, `setMaxClients(n)`, `clearBindings()`, `unbind(ip)`, `slots()`, `snapshot()`; revoke only via `ProxyServer.revoke` |
| `CredentialStore` | SSID suffix + Wi-Fi password, encrypted with Android Keystore | `get()`, `set(suffix, password)` |
| `PowerKeeper` | Partial wake lock + `WIFI_MODE_FULL_LOW_LATENCY` WifiLock, tied to state | `onState(state)` |
| `SystemMonitor` | Wi-Fi, P2P, airplane mode, Data Saver, system hotspot, permissions, thermal status | `events: Flow<SystemEvent>` |
| `HealthWatchdog` | Every 30 s: listener accepts, group exists, egress reachable | `events: Flow<HealthEvent>` |
| `PreflightChecker` / `OemGuide` | Pre-start checks; manufacturer-specific battery guidance | `check(): List<Issue>` |
| `NotificationPresenter`, Compose UI | Notification and screens (§8) | — |

### Session state machine

```
Idle → Preflight → Starting → Running{AwaitingClient | Serving}
Running ⇄ Degraded{LinkDown | NoEgress | WifiOff}
Degraded → Recovering(backoff) → Running
Degraded → Failed(needsUser)
any → Stopping → Idle
```

Desired state ("session should be running") and credentials persist in DataStore / Keystore. Service returns `START_STICKY`; a null-intent restart reloads desired state and re-enters `Starting`.

## 4. Access control and multi-client admission

### Layer 1 — Wi-Fi password (user-defined)

- Settings: SSID suffix (prefixed `DIRECT-xy-`, as the API requires) and password (8–63 ASCII chars, WPA2 rule) with a strength meter.
- Stored encrypted (Keystore); reused every session, so clients auto-rejoin. Never auto-rotated.
- First session cannot start until a password is set. No default password ships.
- Changing the password restarts the group and drops all clients (confirm dialog).
- **Read-back verification:** after `createGroup`, read `WifiP2pGroup.networkName`/`passphrase`. On mismatch (some OEM firmware ignores the config) → `Failed(needsUser)` with an error naming the device model; QR codes are not shown.
- Wi-Fi QR (`WIFI:T:WPA;S:<ssid>;P:<password>;;`) is hidden behind tap-to-reveal.

### Layer 2 — proxy slots (cap 1–3)

- **Slot** = `{id, displayName, token (128-bit random, URL-safe), boundIp?, createdAt, lastSeen, byte counters}`.
- "Add device" creates a slot if `activeSlots < maxClients`, and shows its PAC URL `http://<goIp>:<port>/<token>.pac` (or `<token>.mobileconfig` for iPhone).
- **Identity is the token, not the IP.** Each fetch of a slot's PAC binds that slot to the fetching IP, replacing any previous IP. Clients re-fetch PAC on reconnect, so a new DHCP lease or randomized MAC moves the slot instead of consuming a new one.
- Every proxy request: source IP must be bound to an active slot, else `403`.
- Lowering `maxClients` below the active slot count is refused by `ClientRegistry.setMaxClients`; the UI first asks the user which slot(s) to revoke, then retries.
- Slot records (id, name, token, createdAt) are persisted by the Android layer via `ClientRegistry.snapshot()` and passed back on restart; IP bindings are not persisted (clients re-fetch the PAC on reconnect). Tokens are redacted from every `toString()`.
- Revoke removes one slot and closes its tunnels; password change drops everyone.
- **IP bindings are reset** (`ClientRegistry.clearBindings()`) whenever the group is torn down or recreated (`Degraded(LinkDown)`) or the password changes, because DHCP may hand a former client's address to a different device. Clients re-fetch their PAC on reconnect and rebind. When the P2P layer reports that a specific client left, `unbind(ip)` clears just that address.
- A device that knows the Wi-Fi password but holds no slot can join Wi-Fi but every proxy request gets `403`.
- Per-slot tunnel cap: 256 concurrent. No per-slot bandwidth shaping in v1; bandwidth is shared by ordinary TCP fairness across tunnels.

## 5. Data flow

### Client → host

1. Client joins `DIRECT-xy-…` with the predefined password; group DHCP assigns 192.168.49.x. The group-owner IP is read from `WifiP2pInfo.groupOwnerAddress`, never hard-coded.
2. Client fetches `/<token>.pac` → `ClientRegistry.onPacFetch` binds slot → response:
   `function FindProxyForURL(u,h){return "PROXY <goIp>:<port>";}` (no `dnsResolve`/`isInNet` — the link has no DNS).
3. `ProxyServer` reads the first request line on the single listener port (default 8080; non-root cannot bind <1024):
   - `CONNECT host:port` → TLS tunnel.
   - Absolute-URI request (`GET http://…`) → plain HTTP forward; strip `Proxy-*` hop-by-hop headers.
   - `GET /<token>.pac` / `GET /<token>.mobileconfig` → served locally.
   - Other origin-form paths → `404`; non-`GET` on a token path → `405`; malformed request line/headers → `400`.

### Host → internet

4. Admission: `ClientRegistry.admittedSlot(srcIp)` else `403`. For admitted clients only, connectivity probes (`connectivitycheck.gstatic.com/generate_204`, `www.google.com/gen_204`, `captive.apple.com/hotspot-detect.html`, port 80) are answered locally **only while the cellular network is `VALIDATED`**; otherwise forwarded (and thus failed) so clients aren't told they're online when they aren't. Then the per-slot tunnel cap, else `503`.
5. DNS through the `Egress` (Android: `DnsResolver` bound to the cellular network, cancellable, with a timeout and a TTL-respecting cache owned by the Egress); core adds a `connectTimeoutMs` deadline → `504`.
6. Destination filter after resolution: reject loopback, RFC 1918, link-local, CGNAT `100.64.0.0/10`, `192.0.0.0/24` (includes Android's CLAT address `192.0.0.4`), the P2P subnet, **the host's own interface addresses** (cellular/Wi-Fi global IPv6 etc., as /32 and /128 — connecting to any local address reaches the host regardless of socket binding; the Android layer supplies them and refreshes them on network change), and IPv6 equivalents (`::/96`, `fc00::/7`, `fe80::/10`) → `403`. IPv4-mapped IPv6 addresses are unwrapped before checking. NAT64 (`64:ff9b::/96`) is deliberately **not** blocked — it carries all IPv4-only sites on IPv6-only carriers; decoding the embedded IPv4 is a possible follow-up.
7. Open `SocketChannel`; `cellNetwork.bindSocket(channel.socket())` **before** `connect`. Happy-eyeballs: IPv6 first, IPv4 after 250 ms. No cellular network → immediate `503`.
8. Bidirectional pump with one 16 KB heap buffer per direction (no pool; revisit if profiling shows GC pressure), `TCP_NODELAY`, kernel-default socket buffers, backpressure. Byte counts feed per-slot counters (reported per write). Plain-HTTP forwards carry exactly one request per upstream connection (`Connection: close`); the tunnel closes when the response ends.
9. **Per-direction failure policy.** A *read* failure means that side's peer is dead → the other direction is cancelled. A *write* failure only means the destination stopped accepting data → the other direction is left to drain (Linux/Android keep queued data readable after an RST). The tunnel returns a `TunnelResult(end, error, responseComplete)`; `responseComplete` is true once everything upstream sent reached the client followed by FIN.
10. **Abort rule.** The client connection is aborted (`SO_LINGER(0)` → RST) only when the response to it was cut: `!responseComplete && (UPSTREAM_FAILED || forward && IDLE)`, or a forward cancelled by revoke/stop. Otherwise it is closed normally, so a connection-close-delimited response never looks complete when it wasn't, and a complete one is never destroyed.

### Listener binding

`ServerSocketChannel` binds **only** to the group-owner IP. Never `0.0.0.0` or `::` — cellular IPv6 addresses are often globally routable, so a wildcard bind is an internet-open proxy. Each accepted peer is also checked against the P2P subnet.

### Network change

- Cellular `onLost`/new `onAvailable` → swap the egress handle atomically; close all tunnels bound to the old `Network`; `Degraded(NoEgress)` returns `503` while link and listener stay up.
- Group teardown → `Degraded(LinkDown)`; recreate with identical credentials; clients rejoin and re-fetch PAC (re-binding slots).

### Timeouts

Idle tunnel timeout 5 min; 30 min for Apple push (destination port 5223 or `17.0.0.0/8`).

## 6. Error handling and reliability

### Self-healing (Degraded → Recovering → Running)

| Event | Detection | Response |
|---|---|---|
| Cellular lost/changed | `NetworkCallback` | Close old tunnels, `503` meanwhile, rebind on available |
| P2P group torn down | `WIFI_P2P_CONNECTION_CHANGED_ACTION`, `requestGroupInfo` → null | Recreate with same credentials; backoff 1 s → 2 s → … cap 60 s |
| Wi-Fi off | `WIFI_P2P_STATE_CHANGED_ACTION` | `Degraded(WifiOff)`; notification action opens `Settings.Panel.ACTION_WIFI` (apps cannot enable Wi-Fi on API 29+) |
| Airplane mode | `SystemMonitor` | Link + egress down; auto-recover on exit |
| Process killed | `START_STICKY` + persisted desired state | Restore session; clients auto-rejoin (fixed credentials) |
| Silent stall | `HealthWatchdog` (30 s): `ProxyServer.isAccepting`, group exists, egress reachable | Restart the failing component |
| Host interface addresses change | `LinkProperties` callback | `ProxyServer.updateFilter(DestinationFilter.default(p2pSubnet, hostAddresses))` |

### Needs the user → `Failed(needsUser)` with a specific message

- Permission revoked: `NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK` (API 37 — revocation manifests only as client timeouts, so it is re-checked on every resume and by the watchdog), `POST_NOTIFICATIONS`.
- Firmware ignored requested SSID/password (read-back mismatch).
- 10 consecutive failed recoveries.
- System hotspot enabled (kills the P2P group on single-radio devices).

### Proxy errors

`403` unknown source IP or blocked destination · `503` no egress (`isAvailable == false` or `EgressUnavailableException` mid-dial) or tunnel cap · `502` DNS failure / connect refused · `504` connect timeout (`SocketTimeoutException`) · `400` malformed request or head > 16 KB. Error responses are followed by a short lingering drain (≤ 250 ms / 64 KB) so the client actually receives them. Each tunnel is isolated; one failure never affects others. Routine peer resets are not reported as errors; `onError` is reserved for non-I/O bugs and accept-loop failures.

### Overload protection (pre-admission)

Before a connection is admitted it counts against three caps; over any cap it is reset immediately (`SO_LINGER(0)`): `maxConnections` (832 in flight), `maxPendingHeads` (64 unadmitted in total), `maxPendingHeadsPerPeer` (16 per IP). A connection holds its pending-head slot until admission, including any drained 403/400/PAC response, so a slotless Wi-Fi peer cannot exhaust fds/heap or crowd admitted clients out. The accept loop survives `accept()` failures (e.g. EMFILE) with backoff, reports them rate-limited, and exposes `ProxyServer.isAccepting` for `HealthWatchdog`. All server state is per run, so `stop()`/`start()` never inherits counters.

### Preflight (before every start)

Permissions granted, password set, cellular available, battery-optimization exemption granted, OEM battery settings (Samsung "Never sleeping apps"/Unrestricted; Xiaomi Autostart + No restrictions; OnePlus/ColorOS background activity; link to dontkillmyapp.com).

### Power and thermal

- Partial wake lock while `Running`.
- WifiLock `WIFI_MODE_FULL_LOW_LATENCY` acquired while `Running` (best-effort; GO-side power save is driver-controlled).
- `PowerManager` thermal status ≥ `SEVERE` → throttle new tunnels, warn in notification.

### Diagnostics and privacy

- Ring-buffer file log of state transitions; exportable. `ApplicationExitInfo` reason recorded on startup.
- **No URLs or hostnames logged** — byte and connection counters only.

## 7. Permissions and manifest

- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"` (API 33+)
- `ACCESS_FINE_LOCATION` with `maxSdkVersion="32"` (P2P on API 29–32)
- `ACCESS_LOCAL_NETWORK` (API 37; same NEARBY_DEVICES group, no extra prompt)
- `POST_NOTIFICATIONS` (API 33+)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`; service `foregroundServiceType="connectedDevice"` (no time limit; not `dataSync`, which is capped at 6 h/24 h on API 35+)
- `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

`startForeground()` is called immediately on start, before slow P2P setup.

## 8. UI (Jetpack Compose)

1. **Home** — Start/Stop, session state, cellular indicator, slots `n/max` with per-device name, live up/down counters, Revoke.
2. **Add device** — QR 1: Wi-Fi (tap-to-reveal). QR 2: the slot's PAC URL, iPhone/Android toggle (iPhone → `.mobileconfig`). Short per-platform steps, including "turn off mobile data on the client" and (Android) "set Private DNS to Off/Automatic".
3. **Settings** — SSID suffix, password, max clients (1–3), port (default 8080), prefer 5 GHz, data-usage history.
4. **Setup check** — permissions, battery exemption, OEM steps; re-checked on every app open.
5. **Diagnostics** — state log viewer, Export.

In-app disclosure: "Web and most apps work. Calls, games and some apps won't." Plus a note that carriers define tethering by usage, so this may still breach the carrier's terms.

### Client onboarding

- **iPhone:** scan Wi-Fi QR → open `<token>.mobileconfig` link **in Safari** (only Safari hands profiles to Settings; a third-party default browser may not) → install profile in Settings (contains Wi-Fi payload with `ProxyType=Auto`, `ProxyPACURL`). Manual alternative: Wi-Fi (i) → Configure Proxy → Automatic → PAC URL.
- **Android:** scan Wi-Fi QR → network → Edit → Advanced → Proxy → Proxy Auto-Config → PAC URL.

## 8a. USB sharing to a laptop (adb forward)

A second, independent transport for **one laptop/PC over USB**, added 2026-11-20. It does not use system tethering (`TetheringManager`), so it avoids the carrier entitlement check just as Wi-Fi Direct does.

```
Laptop app → laptop 127.0.0.1:8081 ──adb forward (USB)──► host 127.0.0.1:8081 → USB ProxyServer → cellular
```

- **Host listener:** a second `ProxyServer` instance bound to `127.0.0.1:8081` with `peerSubnet = 127.0.0.0/8`. It shares the `ClientRegistry`, the `Egress` and the `DestinationFilter` with the Wi-Fi listener, so caps, counters, filtering, error mapping and the abort rule are identical. It runs whenever the **"USB sharing"** toggle is on, with or without the Wi-Fi Direct group.
- **Admission: pinned slot.** Enabling USB sharing creates a built-in slot "Laptop (USB)" **pinned** to `127.0.0.1`. It needs no token or PAC fetch; turning on USB debugging and running the laptop script is the deliberate act. Pinned slots:
  - count toward the 1–3 device limit;
  - are never cleared by `clearBindings()` or `unbind()`;
  - can't be stolen: `onPacFetch` from a pinned IP never rebinds it to another slot;
  - are removed when USB sharing is turned off.
- **Accepted exposure:** other apps on the host phone can also reach `127.0.0.1:8081`. They already have mobile data, and the destination filter still blocks the host's loopback and LAN, so this grants nothing new.
- **Laptop side:** `tools/usb-client/connect-usb.ps1` (Windows) does the following:
  - locates `adb`;
  - checks exactly one device is attached;
  - runs `adb forward tcp:8081 tcp:8081`;
  - saves and sets the user's WinINet system proxy to `127.0.0.1:8081`, with `<local>` bypass;
  - waits for Enter, then restores the previous proxy and removes the forward. It also restores on Ctrl+C.

  macOS and Linux users run `adb forward` and set the proxy manually (README).
- **Requirements:** USB debugging stays enabled on the host while sharing, and the laptop needs Android platform-tools (`adb`).
- **Offline laptops: the Laptop kit.** A laptop with no internet can't download `adb`, so the phone fetches it.
  1. "Prepare laptop kit" (Devices → Laptop (USB)) reads Google's SDK repository index (`https://dl.google.com/android/repository/repository2-3.xml`, falling back to `repository2-1.xml`). It finds the current `platform-tools` Windows archive URL and its **SHA-1**, downloads it over the phone's connection, and **verifies the checksum**.
  2. It writes these files to `Download/WiFiProxy-LaptopKit/` via MediaStore:
     - the extracted `platform-tools/`;
     - `connect-usb.ps1`, bundled from `tools/usb-client/` at build time;
     - `connect-usb.cmd`, a double-click launcher running `powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect-usb.ps1"`;
     - `README.txt`.
  3. The user copies the folder to the laptop over USB **File transfer** (MTP). MTP and USB debugging work together.

  The app never redistributes Google's binaries: they're downloaded by the user's phone from Google, which respects the SDK license's redistribution terms. Windows normally binds its built-in WinUSB driver to a Pixel's ADB interface automatically. If it doesn't, the README explains that the Google USB driver is needed (out of scope for the kit).
- **No-install alternative:** a laptop can always use **Wi-Fi Direct + PAC** instead (Plan 2b), which needs nothing installed. Plan 2b follows 2a directly.
- **Coverage:** same as §9. Proxy-aware apps work; UDP, games and apps that ignore the system proxy don't. Windows apps using WinHTTP may need `netsh winhttp import proxy source=ie`; the script prints this hint.

## 9. Expected client coverage

- **Works:** browsers; apps using URLSession/CFNetwork (iOS) or HttpURLConnection/OkHttp (Android); iOS push via PAC.
- **Fails:** all UDP (VoIP/WebRTC media, most games, QUIC — browsers fall back to TCP under a proxy); apps that bypass system proxy (some Flutter/custom-stack apps, games, media SDKs); Android FCM push (port 5228 ignores proxy).

## 10. Testing

- **Unit (JVM):** `SessionController` (fake link/egress/clock, Turbine, `TestDispatcher`); `ClientRegistry` (slot create, token→IP rebind, cap enforcement, revocation, cap reduction); request-line parsing; destination filter; backoff.
- **Loopback integration:** real `ProxyServer` on 127.0.0.1 driven by OkHttp — CONNECT, absolute-URI HTTP, PAC/mobileconfig, `403`/`503`/`400`.
- **Instrumented:** FGS lifecycle, permission flows (UiAutomator), cellular socket binding. Emulator lacks Wi-Fi Direct → link tests on real devices.
- **Manual matrix:** Pixel (Android 15/16/17), Samsung One UI, Xiaomi HyperOS, OnePlus, one API 29 device. Scenarios: 4 h screen-off soak; `dumpsys deviceidle force-idle`; `am set-standby-bucket restricted`; `am kill`; Wi-Fi/data/airplane toggles; dead zone; host joins Wi-Fi mid-session; system hotspot on; reboot; **3 clients concurrently (mixed iPhone + Android)**; password change; slot revoke; client rejoin with new IP.

## 11. Top risks

1. **OEM Wi-Fi Direct variance** — idle group teardown, ignored SSID/passphrase, aggressive GO power save with screen off. Mitigation: read-back verification, recreate-with-same-credentials, watchdog, device matrix.
2. **OEM process killers** despite FGS. Mitigation: battery exemption, OEM guide, `START_STICKY`, `ApplicationExitInfo` telemetry.
3. **Client network-selection quirks** — Wi-Fi Assist / client mobile data, Private DNS strict mode, "No internet" on iOS. Mitigation: PAC, local probe answers, onboarding instructions.
4. **Slot hijack by a password holder** — admission is source-IP + token, and the token travels over plain HTTP on a shared WPA2-PSK link. Anyone who knows the Wi-Fi password and captures a victim's handshake can decrypt a PAC re-fetch and steal the token, or spoof a bound IP; victim and attacker then rebind the slot back and forth. Inherent to the no-client-app design. Mitigation: treat the Wi-Fi password as the real trust boundary, rotate it after sharing with someone untrusted, revoke unknown slots.
5. **Exposure** — wildcard bind or SSRF into host loopback. Mitigation: GO-IP-only bind, peer subnet check, post-resolution destination filter.
6. **Egress correctness under churn** — stale `Network` handle or DNS slipping onto Wi-Fi. Mitigation: bound-or-refuse, atomic handle swap.
