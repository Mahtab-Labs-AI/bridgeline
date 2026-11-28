# Changelog

All notable changes to this project are documented in this file, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) style. Versioning:
`MAJOR.MINOR.PATCH.BUILD` (see "Versioning and releases" in README.md).

Releases from 1.0.0 on are tagged `vX.Y.Z.BUILD` on `main` and published as GitHub releases.
The 0.x entries are reconstructed history; their commit ranges are commit hashes, not tags.

## [Unreleased]

## [1.1.0] - 2026-11-28

### Added
- USB seat Wi-Fi warning: when USB sharing reserves the only client seat (Max clients = 1 with USB
  sharing on), Home warns while the session is active and Settings shows an immediate hint explaining
  how to raise the limit or turn USB sharing off. A running session only picks up a saved limit change
  after sharing restarts, so Home keeps warning until then even if Settings no longer would.
- CI: publish a GitHub release with the built debug APK when a `vX.Y.Z.BUILD` tag is pushed, using the
  matching section of this file as the release body.

## [1.0.0] - 2026-11-21

The first release under the name **Bridgeline**. It shares an Android phone's mobile data with
nearby phones and laptops over Wi-Fi Direct (open mode by default, with optional per-device codes)
or USB, through an app-level proxy rather than system tethering. Idle seats are freed for
newcomers, and the app warns when a VPN would block sharing.

### Added
- CI: GitHub Actions build, unit tests and lint on every push to `develop`/`main`, debug APK
  artifact upload.
- Public name and branding: app label, in-app titles, setup-check/VPN-warning/laptop-kit text,
  adaptive launcher icon (with API 33+ monochrome variant), notification icon, and `docs/brand/`
  logo/icon assets.
- `MAJOR.MINOR.PATCH.BUILD` versioning (`version.properties` + commit-count `BUILD`), shown in
  Settings and the Diagnostics export header.
- "Verbose logging (troubleshooting)" in Settings: off by default, turns itself off after 30
  minutes. While on, Logs gains `[v]` lines per connection (device IP, destination port, bytes,
  duration, why it ended or was refused, upstream failures) and per internal step (Wi-Fi Direct
  group attempts and result codes, link changes, cellular network and VPN changes, watchdog
  rebuilds, filter refreshes) - never website names, URLs, tokens or the Wi-Fi password. Diagnostics
  shows a banner while it's on, and the export includes the verbose lines. Lines are rate-limited
  (per device and in total) so a flood can't wipe the log, and addresses outside the local link
  are never printed.
- `main` is protected: changes arrive only by pull request, with merge commits only and a passing
  "Build and test" check.

### Known limitations
- A VPN on the host that covers Bridgeline breaks Wi-Fi Direct sharing. Exclude the app from the
  VPN (split tunnelling) or turn the VPN off.
- Debug-signed build: install by sideloading. Phones with Google's Advanced Protection only allow
  Play Store apps.
- Not yet tested on hardware: 3 Wi-Fi clients at once, Android 10–12 hosts, and Samsung/Xiaomi
  Wi-Fi Direct behaviour.

Commits: `f5eb923`..`v1.0.0` tag.

## [0.6.0] - 2026-11-21

Idle seat eviction: an auto-admitted (open-mode) device that's been idle long enough frees its
seat for a new device, without the host having to revoke it by hand.

### Added
- Auto slots free themselves on demand when idle (`ClientRegistry`).
- Home shows an idle status for auto devices and mentions seat eviction.

### Changed
- Idle-seat eviction is serialized and its activity tracking hardened against races.
- Idle-seat evictions are logged to Diagnostics; idle-status copy polished.

### Fixed
- Only real evictions are counted; the eviction callback is guarded; small UI nits.

Commits: `8502791`..`d9b0d3b`

## [0.5.0] - 2026-11-21

Open admission: by default, any device that knows the Wi-Fi password can join (no per-device
invite code needed), with a setting to go back to the old flow; plus a VPN warning.

### Added
- Open admission: an unknown token admits as a new device (`ClientRegistry.admitOrCreate`,
  per-listener admission), with the fixed `/proxy.pac` and `/proxy.mobileconfig` paths it uses.
- "Require a code for each device" setting to switch back to per-device invites.
- Open-admission Devices screen: shared setup, connected devices list, Disconnect.
- VPN warning on Home and in the Setup check when a VPN would swallow the proxy's replies.

### Changed
- iPhone invite shows the Automatic URL and the installable profile separately, with Copy, Share
  and a manual-proxy hint.

### Fixed
- Secrets redacted in `HostSettings`/`DeviceInvite`; device-count and credential-change edge cases
  (D7); a sticky resume whose start fails posts "Tap to resume"; dead code removed.

Commits: `43b4d2e`..`28d0381`

## [0.4.0] - 2026-11-21

Wi-Fi Direct sharing (Plan 2b): the host creates a Wi-Fi Direct group that other phones join
directly (no laptop/USB needed), alongside a round of review and device-test fixes.

### Added
- `P2pGroupLink` creates, verifies and recreates the Wi-Fi Direct group; `AndroidP2pApi` wraps
  `WifiP2pManager` and its permissions.
- Wi-Fi credential rules and Keystore-encrypted `CredentialStore`; Wi-Fi slot records persisted
  between sessions.
- `SessionController` (renamed from `UsbSessionController`) serves Wi-Fi Direct clients alongside
  USB; persisted host settings, real device invites, sticky restart.

### Fixed
- `P2pGroupLink` start/stop races and hangs; its lifecycle is now a FIFO Mutex so `stop()` never
  depends on the coroutine scope.
- Device-test fixes: adopting a matching group, waiting for slow group reports, Location-off
  handling on API 29-32, the USB laptop keeping its seat, a network rename keeping saved devices,
  wiping stored credentials only when the blob can never open again, and several repository/UI
  fixes (off-Main work, live Wi-Fi listener checks, inline Settings errors, QR encoding off Main).

Commits: `22d5610`..`a0956bb`

## [0.3.0] - 2026-11-20

USB sharing (Plan 2a): a laptop plugged in over USB gets the proxy via `adb forward`, plus the
offline Laptop kit for a laptop with no internet of its own.

### Added
- Cellular-bound `Egress` (`DnsResolver`, `bindSocket`, host address collection).
- USB session controller and foreground service ("Sharing mobile data" notification); real
  session repository and UI wiring.
- `connect-usb.ps1`/`.cmd` (laptop-side USB sharing script and launcher) and pinned slots for the
  USB loopback client.
- Offline Laptop kit: downloads and verifies platform-tools from Google's own SDK repository and
  writes it plus the bundled scripts to `Download/` via MediaStore.

### Fixed
- No dispatcher hop in `newChannel` (fixed a leak on cancel); complete host address set.
- Session lifecycle serialized with rollback of failed starts; egress callback, literal parsing
  and DNS executor/cache fixes; notification removed on every path to idle; early network
  callbacks and CAS phase transitions Main-confined.

Commits: `a94fe2f`..`b9494ba`

## [0.2.0] - 2026-11-20

UI mockup: the `:app` module's toolchain, session model and the Home/Devices/Settings/Setup
check/Diagnostics screens, wired to a fake in-memory repository.

### Added
- `:app` toolchain and empty skeleton; session model, fake repository and pure helpers (TDD).
- Navigation shell and Home screen; Devices/Add-device screen with real QR rendering; Settings,
  Setup check and Diagnostics screens.

### Fixed
- One-back invite sheet, password warning only shown when changed, consistent screen titles.

Commits: `e3c6927`..`d6604b5`

## [0.1.0] - 2026-11-19

Proxy core (Plan 1): the pure Kotlin/JVM data plane, with no Android dependency.

### Added
- `:proxy-core` Gradle module scaffold.
- CIDR matching and destination filter (hardened against mapped IPv6, the CLAT range, and the
  host's own addresses).
- Client registry with token-bound slots and per-slot caps.
- HTTP proxy request parser and canned responses (hardened against header injection, obs-fold,
  framing-header smuggling and zone ids).
- PAC, iOS mobileconfig and connectivity-probe responses.
- NIO selector loop (`NioLoop`) with suspend `await`, a head reader and a pump; hardened against
  loop death, close races and duplicate waiters.
- Bidirectional tunnel with idle timeout and a per-direction failure policy.
- Egress abstraction and a happy-eyeballs dialer.
- `ProxyServer`: admission, tunnels, probes, revoke, and token-gated PAC/mobileconfig endpoints.

Commits: `8f3ae64`..`4ce1415`
