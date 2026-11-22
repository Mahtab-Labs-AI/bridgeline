# Handoff: Android Wi-Fi Proxy (as of 2026-11-21)

Use this to pick the project up on another machine or in a new Claude Code session. Read it first, then the spec.

## What this is
An Android app that shares a phone's **cellular data** with other devices **without system tethering**, so the carrier's tethering check is not triggered. It runs an app-level HTTP proxy, and every upstream socket is bound to the cellular network ("bound or refuse").
- **Wi-Fi Direct** (Plan 2b, **built**): up to 3 phones or laptops join a `DIRECT-xy-…` group with a user-defined password. **Open admission is the default**: any device that joined the group can use the fixed `/proxy.pac` / `/proxy.mobileconfig` URLs or the manual proxy directly, up to the device limit, with no per-device token. "Require a code for each device" restores the per-device invite/token flow. Nothing is installed on the clients.
- **USB to a laptop** (Plan 2a, **built**): `adb forward` to a loopback listener on the phone. The laptop runs `tools/usb-client/connect-usb.ps1`. Offline laptops get the "Laptop kit" (platform-tools plus scripts) from the phone over USB File transfer.

## Key documents
| File | What |
|---|---|
| `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` | **The spec.** It's the source of truth and was updated after every review. §8a covers USB, §12 covers the Plan 2b decisions |
| `docs/superpowers/plans/2026-11-19-proxy-core.md` | Plan 1: the `:proxy-core` library (done) |
| `docs/superpowers/plans/2026-11-20-ui-mockup.md` | The UI mockup plan (done) |
| `docs/superpowers/plans/2026-11-20-usb-sharing.md` | Plan 2a: real USB sharing plus the laptop kit (done) |
| `docs/superpowers/plans/2026-11-20-wifi-direct.md` | Plan 2b: Wi-Fi Direct sharing, including open admission (done) |
| `README.md` | Build instructions, `:proxy-core` rules, and the **"Handoff notes for Plan 2"**, which are binding contracts for the Android layer |
| `tools/usb-client/README.md` | Laptop-side usage (Windows script, macOS/Linux manual steps) |

## Repository and branches
- **GitHub:** private repo `Mahtab-Labs-AI/bridgeline` (the project's public name), remote `origin`.
- **`main`:** the stable, tested line. It contains Plans 1, 2a (USB) and 2b (Wi-Fi Direct, including open admission and on-demand idle seat eviction). It is **protected** by the `protect-main` ruleset: changes arrive only by pull request, with no force-push and no deletion.
- **`develop`:** the integration branch.
- **Flow:**
  1. Branch features off `develop`, then merge them into `develop`.
  2. Build and test on phones from `develop`.
  3. When it's good, open a PR from `develop` to `main` and merge it.
- **Old feature branches** (`feature/*`) are kept for history.
- **Offline backup:** the newest git bundle in the handoff folder (`android-wifi-proxy-2026-11-21b.bundle`).

## State
- **`:proxy-core`** (pure Kotlin/JVM, **214 tests**): the slot registry with pinned slots and unpersisted per-IP auto slots for open admission, a strict parser, PAC/mobileconfig/probes (including the fixed `/proxy.pac` and `/proxy.mobileconfig` paths), a post-DNS destination filter, a happy-eyeballs dialer, the NIO selector loop, a per-direction tunnel failure policy, and a ProxyServer with pre-admission caps and per-run state.
  - **Idle seat eviction (on demand):** under open admission, a full registry evicts (through `ProxyServer.revoke`) the least-recently-active auto slot idle for at least `idleSeatMs` (`ProxyConfig.DEFAULT_IDLE_SEAT_MS`, 3 minutes) before retrying admission once, on the manual-proxy path and through `LocalContent`'s injected `admit` function (fixed paths and the token-path fallback alike). Pinned and coded slots are never candidates; nothing is evicted while there's room, and there's no background sweeper. The slow (eviction) path is serialized through its own lock, re-trying the fast admission inside it first, so a burst of connections from one newcomer can't evict more than one victim. `ProxyServer.onEvicted` surfaces each eviction to the app for diagnostics.
- **`:app`** (Compose, minSdk 29, targetSdk/compileSdk **36**, **249 tests**):
  - Demo mode (fake data) versus real mode;
  - `CellularEgress` (DnsResolver, bindSocket);
  - `SessionController` (renamed from `UsbSessionController`), running USB and Wi-Fi Direct listeners side by side, plus a foreground service with a serialized lifecycle and `START_STICKY` restart;
  - `P2pGroupLink`, `CredentialStore` (Keystore-encrypted), `SlotStore`, open admission by default with the "Require a code for each device" setting, and the laptop kit.
  - The Devices screen shows "Active now"/"Idle N min" (or "Idle N h" past an hour) under each auto device in open mode, from `Slot.lastActivityMs` via the 1 Hz session poll. `SessionController` logs "Idle device freed its seat for a new one" (no names or IPs) whenever `ProxyServer.onEvicted` fires.
- Zero Kotlin warnings across both modules.

### Verification done
- **Emulator** (API 36 ATD, 2026-11-21): 11/11 scenarios passed; see `.superpowers/sdd/task-9-emulator-report.md`. The Wi-Fi Direct group even came up there, despite heavy CPU starvation. Screenshots were black on ATD under software rendering, so UI evidence is `uiautomator` dumps.
- **Real phones** (2026-11-21):
  - an Android host phone shared cellular data over Wi-Fi Direct to an iPhone and a Pixel 6;
  - it failed until the host's VPN was turned off, which is how the VPN limitation (spec §12 decision 9) was found;
  - open admission was verified, and the iPhone kept working on its old per-device token URL. See `.superpowers/sdd/open-admission-report.md`.

## Open items

1. **Device tests not yet done:**
   - 3 clients at once;
   - sticky restart on a real device;
   - airplane mode and Wi-Fi toggles;
   - 5 GHz on real hardware;
   - OEM SSID read-back (Samsung, Xiaomi);
   - API 29–32 devices (location mode);
   - the selector type on API 29;
   - browser preconnect bursts against the per-peer cap of 16;
   - Wi-Fi lock effectiveness.
2. **Deferred findings** (the main ones; full ledger in `.superpowers/sdd/progress.md`):
   - the password-change window can re-persist inactive slots;
   - teardown race / generation counter;
   - `updateSettings` still does one decrypt plus one encrypt on Main;
   - plain-HTTP header bytes are not counted (core);
   - no Compose UI tests;
   - the tests bind 127.0.0.2, which macOS needs aliased.
3. **Pixel 11 with Advanced Protection** blocks sideloading, even with the device toggle off and the phone restarted. The likely cause is the Google account being enrolled in the Advanced Protection Program, or a work policy. Use another device, or Play Console **Internal testing** (needs a Play developer account, release signing and an AAB).
4. **Planned v2** (spec §1): an optional Android client app (`VpnService` + tun2socks) and SOCKS5.
5. Merge `feature/wifi-direct` into `main` once you're happy.

## Environment setup on a new Windows machine
1. **JDK 21:** `winget install --id EclipseAdoptium.Temurin.21.JDK -e`, or extract the Temurin zip to `%LOCALAPPDATA%\Programs\Temurin\`. `scripts/env.ps1` searches both. Dot-source it in every shell: `. .\scripts\env.ps1`.
2. **Android SDK:**
   - get the Android command-line tools;
   - run `sdkmanager --sdk_root=<sdk> --licenses`;
   - install `platform-tools`, `platforms;android-36`, `build-tools;36.0.0` and, optionally, `emulator` plus `system-images;android-36;google_apis;x86_64`;
   - create `local.properties` (gitignored) with `sdk.dir=<sdk path, escaped>`.
3. **Build:** `.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest`. Versions are pinned: Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.20, and Compose BOM 2026.06.01 (newer BOMs need compileSdk 37 and AGP 9).
4. **Emulator:** if `avdmanager` hangs, write the AVD `.ini` and `config.ini` by hand; see the session notes in the plan.

## This machine's environment (2026-11-21)
- **The Windows PC is a VMware VM** with no GPU (VMware SVGA 3D). The Android emulator only runs with software rendering: ATD images show black screens, and `-gpu host` hangs. The emulator image was removed afterwards to save disk space.
- **JDK 21 (Temurin)** is in `%LOCALAPPDATA%\Programs\Temurin\` (no winget). `scripts/env.ps1` finds it.
- **The SDK** is in `%LOCALAPPDATA%\Android\Sdk`. `sdkmanager` now delegates to the new `android` CLI; install packages with `android.exe --sdk=<sdk> sdk install platforms/android-36` (slash-separated package names).
- **Android Studio was uninstalled.** It isn't needed.
- **Write `local.properties` with escaped backslashes.** The Bash tool collapses `\\`, so use a proper editor.
- **Branches:** `main` (309ef95) has only Plan 1 and Plan 2a; `feature/wifi-direct` (head `051b9e6`) adds Plan 2b and is not yet merged. The backup is the git bundle in the handoff folder.
- **Install note:** debug APKs built on different machines have different debug keys, so uninstall the old app before installing a build from another machine.

## Working conventions used so far
- Superpowers workflow: brainstorm → spec → plan → subagent-driven implementation, with a spec-compliance review and a code-quality review per task, plus a final branch review.
- Commit by **explicit paths only**. Check `git diff --cached --name-only` before committing, because parallel agents share the index.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 (1M context)`.
- Never change the host PC's proxy settings in tests; use `connect-usb.ps1 -DryRun`.
