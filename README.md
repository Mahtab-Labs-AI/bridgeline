![Bridgeline logo](docs/brand/logo.svg)

# Bridgeline (Android Wi-Fi Proxy)

Shares a host Android phone's cellular data with up to 3 phones over a Wi-Fi Direct group, using an
app-level HTTP proxy (no system tethering). Design: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`.
Brand assets: `docs/brand/`.

## Modules

| Module | Status | What |
|---|---|---|
| `:proxy-core` | Plan 1 (done) | Pure Kotlin/JVM proxy data plane: slots/admission, parser, PAC/mobileconfig/probes, destination filter, dialer, NIO tunnel server |
| `:app` | Plans 2a (USB) and 2b (Wi-Fi Direct), done | Android host app (service, Wi-Fi Direct, USB, cellular egress, UI) |

`:proxy-core` has 214 tests and `:app` has 249, all green, with zero Kotlin warnings.

## Build and test (Windows)

```powershell
. .\scripts\env.ps1          # puts JDK 21 on JAVA_HOME for this session
.\gradlew.bat :proxy-core:test
```

## Using it

- **Wi-Fi Direct (open mode, the default):** start sharing, then join the host's `DIRECT-xy-…` group with its password. Any joined device can then use `http://<owner>:<port>/proxy.pac` (Automatic/Proxy Auto-Config) or the manual proxy `<owner>:<port>` directly, no PAC fetch needed first. iPhones can instead install `/proxy.mobileconfig` in Safari. "Require a code for each device" in Settings switches back to the per-device invite/token flow.
- **USB (laptop):** turn on "USB sharing", then run `tools/usb-client/connect-usb.ps1` on the laptop (Windows). It finds `adb`, forwards `tcp:8081`, and sets the laptop's system proxy for you.
- **VPN note:** a VPN on the host that covers this app routes the proxy's replies to Wi-Fi Direct clients into the tunnel, so those clients time out. The app warns on Home and in the Setup check. Fix by excluding this app from the VPN (split tunnelling) or turning the VPN off; it can't be fixed in-app without root.
- **Troubleshooting:** turn on "Verbose logging (troubleshooting)" in Settings, reproduce the problem, and Export the Diagnostics log **before** it turns itself off after 30 minutes (turning off trims the log to the last 500 lines). It adds per-connection detail but never website names.

## Security and privacy

Devices talk to Bridgeline as a plain HTTP proxy (`http://<owner>:<port>`), not HTTPS. Here is what that does and doesn't expose.

- **The Wi-Fi link is encrypted.** The Wi-Fi Direct group (`DIRECT-xy-…`) uses WPA2 with your password, so everything sent over the air between a device and the host is encrypted. That includes the proxy traffic. Someone nearby without the password can't read it.
- **HTTPS stays end-to-end encrypted.** For an `https://` site (almost all sites and apps), the device asks the proxy for `CONNECT site:443`, and from then on the proxy only relays encrypted bytes. TLS runs from the device all the way to the website. The proxy, the host phone and the mobile carrier can see **which site** is contacted and how much data moves, but **never the content**: passwords, pages and messages stay private.
- **What is exposed:**
  - Plain `http://` sites are unencrypted, as they would be on any network.
  - Someone who already knows the Wi-Fi password can see site names in the `CONNECT` lines and the setup file (`proxy.pac`, or per-device codes in code mode). WPA2 lets a password holder who captures a device joining decrypt that device's Wi-Fi traffic. They still can't read the content of HTTPS connections.
- **Why not an HTTPS proxy?** Android's and iOS's system proxy settings and PAC files only support a plain HTTP proxy. An encrypted connection to the proxy would work only in some browsers, and every device would have to trust a certificate from the host.
- **Recommendations:**
  - The Wi-Fi password is the real security boundary. Use a strong one and share it only with people you trust.
  - Change it after sharing with someone you don't fully trust; changing it also disconnects everyone.
  - Prefer `https://` for anything sensitive.
- **No logging of what you browse.** Bridgeline never logs website names or URLs. The optional verbose troubleshooting log records only device IPs, ports, sizes and timings, and it turns itself off after 30 minutes.
- **Reporting a vulnerability:** use GitHub's private vulnerability reporting (**Security → Report a vulnerability**) rather than a public issue.

## Versioning and releases

Bridgeline uses **MAJOR.MINOR.PATCH.BUILD** (e.g. `1.2.3.45`):

- **MAJOR** - big redesigns.
- **MINOR** - new features.
- **PATCH** - bug fixes.
- **BUILD** - auto-counted per build (the commit count, `git rev-list --count HEAD`; identical locally and on CI). It is only ever read, never hand-edited.

`version.properties` at the repo root (`major`/`minor`/`patch`) is the single source of truth for the first three numbers; `app/build.gradle.kts` reads it and computes BUILD at configuration time, so `versionName`/`versionCode` and the in-app "Bridgeline x.y.z.build" string (Settings, Diagnostics export) always agree.

Release procedure:

1. Bump `version.properties` for the kind of change (major/minor/patch).
2. Update `CHANGELOG.md`: move `## [Unreleased]` into a new `## [x.y.z] - YYYY-MM-DD` heading (date it, keep its Added/Changed/Fixed/Security lists user-meaningful rather than one line per commit).
3. Open a PR `develop` -> `main`, wait for the required "Build and test" check, and merge with a normal **merge commit**. The `main` ruleset only allows that method: BUILD is the commit count, so a squash or rebase merge could lower `versionCode`, which Android and the Play Store reject.
4. After it merges, tag `main` as `vX.Y.Z.BUILD` and create a GitHub release with that changelog section and the built APK.

## `:proxy-core` rules

- Keep it Android-compatible (API 29). Use only `java.nio`, `java.net`, `java.util`, `java.security` and kotlinx-coroutines.
- No `java.net.http`, no `java.nio.file`, no JDK 11+ library APIs.
- `ProxyServer` refuses wildcard and non-IPv4 bind addresses. Always bind to the group-owner IP.
- Close any channel that has been awaited on a `NioLoop` through `NioLoop.closeChannel`. Closing it directly delays the RST and the fd release until the selector wakes.
- Await from a real dispatcher. `NioLoop` resumes continuations on its own thread.
- `DirectEgress` is for tests only. On Android, upstream sockets and DNS must go through the cellular `Egress` (Plan 2).

## Handoff notes for Plan 2 (Android host)

Code review turned up these points. Status below reflects Plan 2a (USB) and Plan 2b (Wi-Fi Direct), both now done.

**Egress** — done.
- `newChannel()` must never return an unbound channel ("bound or refuse").
- If binding fails, it must close the channel it opened.
- It throws `EgressUnavailableException` when the network is gone.
- `resolve()` must be cancellable and time-bounded. Use `DnsResolver` with a timeout, not a blocking `getAllByName` in `withContext(IO)`.

**Filter** — done.
- Pass the phone's own interface addresses as `hostAddresses` to `DestinationFilter.default(...)`.
- Call `ProxyServer.updateFilter` whenever `LinkProperties` change.

**Registry** — done.
- Call `ClientRegistry.clearBindings()` on group teardown or recreate, and on password change.
- Persist `snapshot()` and restore it as `initial`.

**Watchdog** — done.
- Watch `ProxyServer.isAccepting`. If it is false (for example the NIO loop died), the server socket may still be bound, so recover with `stop()` followed by `start()`.
- `onError` reports only non-I/O bugs and accept-loop failures.
- `stop()` blocks for up to about 1 s, because it joins the loop thread. Never call it on the main thread.

**Revoke** — done.
- Revoke a slot only through `ProxyServer.revoke`, which also cancels the slot's live tunnels. `ClientRegistry.revoke` is `internal`.

**Lifecycle** — done.
- `ProxyConfig` is immutable and carries the bind address and Wi-Fi credentials. When the password, the group-owner IP or the egress network changes, `stop()` the server and build a new `ProxyServer`. That closes every tunnel; the spec requires it ("password change drops everyone"; tunnels bound to the old `Network` are dead anyway).

**Egress implementation**
- Use one long-lived Egress object that swaps its current `Network` internally. `ProxyServer` and `Dialer.forEgress` capture the instance.
- Timeouts must surface as `IOException`, for example `SocketTimeoutException`, never as `TimeoutCancellationException`. A cancellation exception escapes `handle()` silently, so the client gets no 504.
- `newChannel()` must not leak on cancellation. If it suspends after opening the socket, close the socket when cancelled.
- The TTL-respecting DNS cache from the spec lives in the Egress.

**Host addresses**
- Collect `hostAddresses` from `LinkProperties.getAllLinkAddresses()` for every interface: the cellular base link, stacked CLAT links, IPv6 temporary addresses, and Wi-Fi STA global IPv6.

**Stats**
- There is no stats `Flow`. Poll `registry.slots()` for per-slot bytes and active tunnels.

**Device tests to add**
1. Check `Selector.open().javaClass` on API 29. We expect a poll-based selector.
2. Confirm on a device or emulator that the zero-interest HUP spin is caught by `NioLoop`'s idle-key sweep.
3. Confirm that an SO_LINGER(0) close of a registered channel sends RST promptly.
4. Run a Tunnel round trip on API 29.
5. Check that browser preconnect bursts don't hit the per-peer pending-head cap of 16, which would show up as spurious resets.

**Toolchain**
- No Android SDK was found via `ANDROID_HOME`, but `C:\Program Files\Android\openjdk\jdk-21.0.8` exists. Android tooling may be partly installed.

## Licence

Bridgeline is released under the [MIT License](LICENSE). Copyright (c) 2026 Mahtab Labs AI.
