# Android Wi-Fi Proxy

Shares a host Android phone's cellular data with up to 3 phones over a Wi-Fi Direct group, using an
app-level HTTP proxy (no system tethering). Design: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`.

## Modules

| Module | Status | What |
|---|---|---|
| `:proxy-core` | Plan 1 (done) | Pure Kotlin/JVM proxy data plane: slots/admission, parser, PAC/mobileconfig/probes, destination filter, dialer, NIO tunnel server |
| `:app` | Plan 2–3 | Android host app (service, Wi-Fi Direct, cellular egress, UI) |

## Build and test (Windows)

```powershell
. .\scripts\env.ps1          # puts JDK 21 on JAVA_HOME for this session
.\gradlew.bat :proxy-core:test
```

## `:proxy-core` rules

- Keep it Android-compatible (API 29). Use only `java.nio`, `java.net`, `java.util`, `java.security` and kotlinx-coroutines.
- No `java.net.http`, no `java.nio.file`, no JDK 11+ library APIs.
- `ProxyServer` refuses wildcard and non-IPv4 bind addresses. Always bind to the group-owner IP.
- Close any channel that has been awaited on a `NioLoop` through `NioLoop.closeChannel`. Closing it directly delays the RST and the fd release until the selector wakes.
- Await from a real dispatcher. `NioLoop` resumes continuations on its own thread.
- `DirectEgress` is for tests only. On Android, upstream sockets and DNS must go through the cellular `Egress` (Plan 2).

## Handoff notes for Plan 2 (Android host)

Code review turned up these points:

**Egress**
- `newChannel()` must never return an unbound channel ("bound or refuse").
- If binding fails, it must close the channel it opened.
- It throws `EgressUnavailableException` when the network is gone.
- `resolve()` must be cancellable and time-bounded. Use `DnsResolver` with a timeout, not a blocking `getAllByName` in `withContext(IO)`.

**Filter**
- Pass the phone's own interface addresses as `hostAddresses` to `DestinationFilter.default(...)`.
- Call `ProxyServer.updateFilter` whenever `LinkProperties` change.

**Registry**
- Call `ClientRegistry.clearBindings()` on group teardown or recreate, and on password change.
- Persist `snapshot()` and restore it as `initial`.

**Watchdog**
- Watch `ProxyServer.isAccepting`.
- `onError` reports only non-I/O bugs and accept-loop failures.

**Device tests to add**
1. Check `Selector.open().javaClass` on API 29. We expect a poll-based selector.
2. Confirm on a device or emulator that the zero-interest HUP spin is caught by `NioLoop`'s idle-key sweep.
3. Confirm that an SO_LINGER(0) close of a registered channel sends RST promptly.
4. Run a Tunnel round trip on API 29.

**Toolchain**
- No Android SDK was found via `ANDROID_HOME`, but `C:\Program Files\Android\openjdk\jdk-21.0.8` exists. Android tooling may be partly installed.
