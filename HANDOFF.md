# Handoff: Android Wi-Fi Proxy (as of 2026-11-20)

Use this to pick the project up on another machine or in a new Claude Code session. Read it first, then the spec.

## What this is
An Android app that shares a phone's **cellular data** with other devices **without system tethering**, so the carrier's tethering check is not triggered. It runs an app-level HTTP proxy, and every upstream socket is bound to the cellular network ("bound or refuse").
- **Wi-Fi Direct** (Plan 2b, not built yet): up to 3 phones or laptops join a `DIRECT-xy-…` group with a user-defined password and use a PAC proxy. Nothing is installed on the clients.
- **USB to a laptop** (Plan 2a, **built**): `adb forward` to a loopback listener on the phone. The laptop runs `tools/usb-client/connect-usb.ps1`. Offline laptops get the "Laptop kit" (platform-tools plus scripts) from the phone over USB File transfer.

## Key documents
| File | What |
|---|---|
| `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` | **The spec.** It's the source of truth and was updated after every review. §8a covers USB |
| `docs/superpowers/plans/2026-11-19-proxy-core.md` | Plan 1: the `:proxy-core` library (done) |
| `docs/superpowers/plans/2026-11-20-ui-mockup.md` | The UI mockup plan (done) |
| `docs/superpowers/plans/2026-11-20-usb-sharing.md` | Plan 2a: real USB sharing plus the laptop kit (done) |
| `README.md` | Build instructions, `:proxy-core` rules, and the **"Handoff notes for Plan 2"**, which are binding contracts for the Android layer |
| `tools/usb-client/README.md` | Laptop-side usage (Windows script, macOS/Linux manual steps) |

## Branches (no remote; there is a git bundle)
`main` (the spec and plan only) → `feature/proxy-core` → `feature/ui-mockup` → **`feature/usb-sharing`** (latest; work here). **Nothing is merged into `main` yet.**

## State
- **`:proxy-core`** (pure Kotlin/JVM, 142 tests): the slot registry with pinned slots, a strict parser, PAC/mobileconfig/probes, a post-DNS destination filter, a happy-eyeballs dialer, the NIO selector loop, a per-direction tunnel failure policy, and a ProxyServer with pre-admission caps and per-run state. It went through several in-depth review rounds.
- **`:app`** (Compose, minSdk 29, targetSdk 36, 89 tests):
  - five screens;
  - Demo mode (fake data) versus real mode;
  - `CellularEgress` (DnsResolver, bindSocket);
  - the `UsbSessionController` plus a foreground service with a serialized lifecycle;
  - the laptop kit.
- **Verified on the API 36 emulator.** The PC browsed through the app via adb forward:
  - https and http both returned 200;
  - a 10 MB download completed at about 2.6 MB/s;
  - a CONNECT to the phone's own localhost got 403;
  - stopping left everything clean.
- **Not verified live:** wake-lock renewal after 10 minutes with the screen off, and a real switch between cellular networks. Both are covered by unit tests only.
- The **last review round was skipped** (fixes `4b6febe`) at the user's request. The changes were tested but not re-reviewed.

## Open items / next steps
1. **Installing on a Pixel 11 was blocked** with "Advanced Protection only allows apps from Google Play…", even though the device toggle was off and the phone had been restarted. The likely cause is that the Google account is enrolled in the Advanced Protection Program, or a work policy enforces it. The user is trying another device. A path that works with Advanced Protection on: release signing → AAB → Play Console **Internal testing** (needs a Play developer account).
2. **Plan 2b, Wi-Fi Direct.** P2pGroupLink, CredentialStore, the Add-device invite flow made real, `clearBindings()` on group teardown, slot persistence via `snapshot()`, and restart on process death. The user asked for this right after 2a.
3. A final re-review of commit `4b6febe` (the lifecycle fixes R1–R6).
4. Merge the branches into `main` once you're happy.
5. Device tests listed in the README:
   - the selector type on API 29;
   - the HUP-spin sweep;
   - the SO_LINGER(0) RST;
   - browser preconnect bursts against the per-peer cap of 16.

## Environment setup on a new Windows machine
1. **JDK 21:** `winget install --id EclipseAdoptium.Temurin.21.JDK -e`, or extract the Temurin zip to `%LOCALAPPDATA%\Programs\Temurin\`. `scripts/env.ps1` searches both. Dot-source it in every shell: `. .\scripts\env.ps1`.
2. **Android SDK:**
   - get the Android command-line tools;
   - run `sdkmanager --sdk_root=<sdk> --licenses`;
   - install `platform-tools`, `platforms;android-36`, `build-tools;36.0.0` and, optionally, `emulator` plus `system-images;android-36;google_apis;x86_64`;
   - create `local.properties` (gitignored) with `sdk.dir=<sdk path, escaped>`.
3. **Build:** `.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest`. Versions are pinned: Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.20, and Compose BOM 2026.06.01 (newer BOMs need compileSdk 37 and AGP 9).
4. **Emulator:** if `avdmanager` hangs, write the AVD `.ini` and `config.ini` by hand; see the session notes in the plan.

## Working conventions used so far
- Superpowers workflow: brainstorm → spec → plan → subagent-driven implementation, with a spec-compliance review and a code-quality review per task, plus a final branch review.
- Commit by **explicit paths only**. Check `git diff --cached --name-only` before committing, because parallel agents share the index.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 (1M context)`.
- Never change the host PC's proxy settings in tests; use `connect-usb.ps1 -DryRun`.
