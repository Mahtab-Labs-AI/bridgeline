# USB seat Wi-Fi warning: implementation verification

Date: 2026-11-26. Independent review and exact-HEAD automated verification passed at the reviewed implementation revision. M1 Settings preview and Mac USB/Chrome connectivity passed by user report; M2-M6 warning acceptance remains unverified. The draft PR preserves these gaps. See the originating-session addendum for evidence and limitations.

Worktree: `C:\source\bridgeline-usb-seat-warning`
Branch: `feature/usb-seat-wifi-warning`
Base: `e6af3c1d6214a00c3565e68278fe9dd09fd6d926` (`develop`, no baseline drift).
Task 1 commit: `1101066bc7fd19eae8b503a3b3d64ec33fa32abf`.

The final source/documentation commit and the APK generated afterward are identified in the archive receipt:
`C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\IMPLEMENTATION-RESULTS.md`.
Recording the final artifact there avoids a version-changing documentation commit after packaging.

## Behavior verified by JVM tests

- The controller returns null before startup and after completed stop or failed-start rollback.
- USB on with limits 1/2/3 yields capacities 0/1/2; USB off with limit 1 yields 1. Inputs 0/4 clamp to the same startup bounds as the registry. Both admission modes are exercised.
- Changing the configuration source from 1 to 2/3 and from 2/3 to 1 leaves active capacity unchanged through RUNNING and DEGRADED; stop/start applies the new value.
- Occupied Wi-Fi seats and inactive saved coded devices do not reduce configured capacity.
- The fake snapshots its explicit USB callback and limit at STARTING, retains capacity through saved edits, rejected saves, degradation/recovery and STOPPING, and clears it at IDLE. Limit-1 cases revoke both seed slots first; each added scenario closes its repository.
- The existing SettingsSaver test still verifies that a limit-only save does not restart sharing. No SettingsSaver implementation/test changes were needed.

The two RED runs failed at Kotlin test compilation for the intended missing interfaces: controller `wifiSeatCapacity`, then fake constructor `usbEnabled`. Neither RED run was an environment failure. Both were followed by passing targeted tests. These are JVM state tests, not Compose UI tests.

## Commands and real results

All commands ran from the feature worktree after `. .\scripts\env.ps1`, using JDK 21 selected by that script. Exact expanded commands, timestamps and exit codes are in each `*.command.txt` and `*.result.json` under:
`C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence`.

| Run | Exit | Absolute raw log |
| --- | --- | --- |
| baseline | 0 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\baseline.log` |
| controller-red | 1 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\controller-red.log` |
| controller-green | 0 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\controller-green.log` |
| fake-red | 1 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\fake-red.log` |
| state-green | 0 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\state-green.log` |
| task1-assemble | 0 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\task1-assemble.log` |
| delivery | 0 | `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\delivery.log` |

Task arguments (all use `--max-workers=1`):

```powershell
# baseline
.\gradlew.bat :proxy-core:test :app:testDebugUnitTest :app:lintDebug --max-workers=1
# controller-red and controller-green
.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.service.SessionControllerTest' --max-workers=1
# fake-red
.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.session.FakeSessionRepositoryTest' --max-workers=1
# state-green
.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.service.SessionControllerTest' --tests 'com.wifiproxy.app.session.FakeSessionRepositoryTest' --tests 'com.wifiproxy.app.session.SettingsSaverTest' --max-workers=1
# task1-assemble (pre-warning artifact; no installation)
.\gradlew.bat :app:assembleDebug --max-workers=1
# delivery
.\gradlew.bat :proxy-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1
```

Local memory flags appended to every invocation: `--no-daemon --console=plain`,
`-Dorg.gradle.jvmargs=-Xmx768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
`-Pkotlin.compiler.execution.strategy=in-process`, and
`--init-script C:\Users\behzad\AppData\Local\Temp\usb-seat-low-memory.gradle`.
That temporary init script sets all Test tasks to `maxHeapSize = '256m'`, `maxParallelForks = 1`,
and `jvmArgs '-XX:ActiveProcessorCount=2'`; process-local `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`.
The limits follow the prior documented machine workaround (`artifacts/IMPLEMENTATION.md` in the archive).
No tracked/global Gradle or JDK settings changed. Builds remained serial; no OOM retry was necessary.
Process-local Git safe.directory entries cover only this worktree and the verified original repository,
so the build's Git commit-count version source works without global configuration changes.

Counts read from JUnit XML:

| Run | Core | App | Failures/errors/skips |
| --- | --- | --- | --- |
| Baseline | 217 | 249 | 0/0/0 |
| Controller GREEN | not rerun | 65 | 0/0/0 |
| Joint state GREEN | not rerun | 81 (controller 65, fake 11, SettingsSaver 5) | 0/0/0 |
| Delivery | 217 | 255 | 0/0/0 |

The delivery core test task reused its up-to-date baseline outputs; core source/tests are unchanged.
Absolute current report locations:

- `C:\source\bridgeline-usb-seat-warning\proxy-core\build\test-results\test`
- `C:\source\bridgeline-usb-seat-warning\proxy-core\build\reports\tests\test\index.html`
- `C:\source\bridgeline-usb-seat-warning\app\build\test-results\testDebugUnitTest`
- `C:\source\bridgeline-usb-seat-warning\app\build\reports\tests\testDebugUnitTest\index.html`
- `C:\source\bridgeline-usb-seat-warning\app\build\reports\lint-results-debug.xml` (and `.html`/`.txt`)

Frozen XML and summaries: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\baseline`, `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\controller-green`,
`C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\state-green`, and `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\delivery`.

Kotlin compiler warnings: 0 in successful runs. The baseline reports an SDK XML tool-version warning
(SDK XML version 4 encountered by tooling supporting up to 3); it is an environment warning, not a Kotlin warning.
The first APK assembly reports that `libandroidx.graphics.path.so` could not be stripped and was packaged as-is.
No code or dependency change was made to address these existing tooling messages.

## Lint baseline comparison

Baseline and delivery each have 10 warnings and 1 hint, with 0 errors and 0 new/removed findings.
Compared identifier, severity, message and source file, preserving multiplicity; line-only shifts are recorded separately.
Exact issue lists equal: `False`; line/location shifts: `2`.
Machine-readable difference: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\lint-comparison.json`.
Existing identifiers: OldTargetApi, BatteryLife, ModifierParameter, DataExtractionRules,
ObsoleteSdkInt (3), AutoboxingStateCreation (hint), UnusedResources, UseKtx (2).

## Deferred device acceptance

No APK was installed. No physical-device acceptance flow was run. Device model/API and UI screenshots
are not recorded because the user reserved device work for the originating session.

Pre-change device reproduction: **DEFERRED TO ORIGINATING SESSION**.
The Task 1 pre-warning APK was built successfully at `1101066bc7fd19eae8b503a3b3d64ec33fa32abf`;
absence of the warnings was not reproduced on a device in this session.

| Case | Required check | Status |
| --- | --- | --- |
| M1 | Idle USB on + limit 1: both hints; slider 2/3 clears previews, back to 1 restores; USB off clears | **DEFERRED TO ORIGINATING SESSION** |
| M2 | Active zero-capacity Home banner, locked USB stop instructions, slider save/restart instructions, error colors and simultaneous VPN-banner layout | **DEFERRED TO ORIGINATING SESSION** |
| M3 | Running limit 1 to 2: preview changes; Home before Save, after Save and after restart | **DEFERRED TO ORIGINATING SESSION** |
| M4 | Running limit 2 to 1 with no saved Wi-Fi devices: Home before Save, after Save and after restart | **DEFERRED TO ORIGINATING SESSION** |
| M5 | Unsaved/rejected edits and loading/unreadable settings preserve active state, validation/locking and visible save rejection | **DEFERRED TO ORIGINATING SESSION** |
| M6 | IDLE/null/positive capacity hides Home warning, including occupied seats; startup/degradation/stop/restart has no stale banner | **DEFERRED TO ORIGINATING SESSION** |

Screenshots still required: Home warning, locked USB hint, and pending higher-limit state, without credentials/client details.
No deferred manual checkbox is marked complete in the implementation plan.

## Scope and review handoff

Changes are confined to the six planned app implementation/test files for Task 1, the two planned screens,
the copied reviewed design/plan, and this verification document. No proxy-core, admission, registry mutation,
defaults, dependencies, lifecycle locks, USB switch enablement, slider validation, or save behavior changes.
Settings warning priority preserves stop/restart instructions and independent save rejection text.
Home uses `activeWifiSeatCapacity == 0 && phase != IDLE`, so saved/edited limits cannot change its warning before restart.

Rulings: Git worktree fallback is required because the native API cannot select the requested sibling path/repository/branch;
native PowerShell plus an archive ledger replaces Bash bookkeeping; independent whole-branch review and all device checks
remain assigned to the fresh review/originating sessions by explicit user instruction. Costs: manual Git worktree management,
custom orchestration, and pending independent review/device evidence. No product/design scope deviation was required.

Review prompt: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\02-review.md`.
Keep the branch/worktree intact. Passing automated checks establishes readiness for independent review, not device verification.

## Originating-session final verification (2026-11-26)

Independent code review completed with no material findings at `00ef04b243022ec37e8c133a6ebcba5054095c0f`. The originating session then independently reran the full delivery command at that exact clean HEAD with `--rerun-tasks`, the recorded serial low-memory configuration, and no product-source changes.

- Run: `final-verification-20261126-exact-head`; exit 0, BUILD SUCCESSFUL in 7m 16s, 60 actionable tasks executed.
- Fresh JUnit XML: core 217 and app 255; zero failures, errors, or skipped tests. Both test tasks actually executed, and all report modification times follow the run start.
- Kotlin compiler warnings: 0. Fresh lint report: 10 warnings and 1 hint, zero errors and no new/removed findings compared with the frozen baseline. The existing SDK XML tooling and unstripped native-library notices remain separate tooling messages.
- Fresh APK: `com.wifiproxy.app`, label Bridgeline, version 1.0.0.139/code 139, 63,256,588 bytes. SHA-256 `A742BDA60588ADA8B5436B8C5EB78AB4C5D92C3E4154D43804077824EBBAA9B5`, byte-identical to the independently reviewed copy. No APK installation occurred.
- Verified distribution copy: `C:\source\android-wifi-proxy-handoff\artifacts\Bridgeline-USB-seat-warning-1.0.0.139-verified.apk`.
- Evidence root: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence`. Distinct final-run command/result/log, fresh frozen JUnit/lint XML under `final-verification-20261126-exact-head`, and `final-verification-20261126-exact-head.audit.json` preserve this session's results. Earlier implementation/review evidence was not overwritten.
- The audit confirmed original and diagnostics checkouts clean at their recorded HEADs. The feature checkout was clean through the tests and audit; at that verification point the subsequent documentation-only update was kept uncommitted to preserve the reviewed HEAD and commit-count-based APK version. No implementation or test source changed.
- ADB detected no USB-connected devices. Intended device model/API and installed-app details remain unrecorded. M1-M6 and pre-warning physical reproduction remain UNVERIFIED; the sing-box tests are separate evidence and do not satisfy these cases.

Current disposition: AUTOMATED VERIFICATION PASSED; DEVICE ACCEPTANCE PENDING. This addendum supersedes earlier statements that independent review or exact-HEAD automated reruns were still pending. No merge, push, release, or publication occurred.

### Subsequent user device evidence (2026-11-26)

The user identified the phone as Pixel 11 and reported the Settings preview checks worked (M1 PASS, user-reported). Android API, installed package/version and screenshots remain unrecorded. M2-M6 and pre-warning device reproduction remain UNVERIFIED.

On the Mac, supplied Terminal output subsequently showed an authorized Pixel_11 ADB device and successful `./adb -d forward tcp:8081 tcp:8081` (output `8081`). The user then executed:

```bash
curl --noproxy "" --proxy http://127.0.0.1:8081 --connect-timeout 10 --max-time 30 -I https://dell.com/
```

Output showed `HTTP/1.1 200 Connection Established`, followed by `HTTP/1.0 301 Moved Permanently` and `Location: https://www.dell.com/`. This verifies a successful CONNECT tunnel and HTTPS response through the configured local proxy/ADB forward, based on user-executed Terminal evidence. Curl exit status was not supplied. Mac Wi-Fi off and Pixel cellular on were instructed but not independently captured. This request does not establish browser configuration, sustained connectivity, package identity, or warning Save/restart acceptance. Earlier Mac ADB startup failures were bypassed with `ADB_USB_LEGACY=1 ./adb start-server`; the precise default-backend failure cause remains unknown. Device identifiers and email headers were omitted.

Full current ledger: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\FINAL-VERIFICATION.md`. This addendum was initially kept uncommitted for the version-139 device test; no product/test source changed. Overall device acceptance remains pending.

### Chrome browsing follow-up (user-reported)

Following successful explicit curl requests, the user reported that Chrome could not browse and `scutil --proxy` returned an empty dictionary. The session supplied graphical FoxyProxy setup for an individual HTTP proxy at `127.0.0.1:8081`, blank credentials, selected for all websites. The user then confirmed "it is working with foxyproxy". Chrome browser connectivity with that configuration is PASS by user report. Mac Wi-Fi off was instructed but not separately confirmed; browser/extension versions, loaded pages and screenshots were not supplied. This does not establish automatic forwarding recovery or M2-M6 warning acceptance. The archive FINAL-VERIFICATION.md contains the current detailed ledger. No implementation or test source changed.

## Draft PR handoff

The user authorized a draft PR for `feature/usb-seat-wifi-warning` targeting `develop`. Only the verification record is being added after the independently reviewed implementation revision `00ef04b243022ec37e8c133a6ebcba5054095c0f`; application/core/test sources remain unchanged. The documentation commit increases the commit-count-based BUILD from 139 to 140. Device observations above apply to the user's installed test setup, whose package/version was not captured; they are not observations of a new version-140 installation.

The PR must remain draft pending M2-M6 and outstanding device/build identity details. No merge or release is authorized. Mac laptop-kit implementation is separate future work. PR-preparation commands, fresh check receipts and artifact identity are recorded under the archive handoff evidence with distinct `pr-preflight-*` / `pr-head-*` names, rather than overwriting the reviewed or earlier final-verification evidence.
Fresh pre-commit PR check `pr-preflight-20261126` completed with exit 0 (`BUILD SUCCESSFUL in 9m 16s`, all 60 actionable tasks executed). Independent report inspection confirmed core 217 + app 255 tests, zero failures/errors/skips, all JUnit XML newer than run start, zero Kotlin compiler warnings, and no new/removed lint findings (10 existing warnings + 1 hint). The rebuilt version-139 APK retained SHA-256 `A742BDA60588ADA8B5436B8C5EB78AB4C5D92C3E4154D43804077824EBBAA9B5`. The archive preserves the command/result/log, frozen reports, package badging and `pr-preflight-20261126.audit.json`. This run precedes the documentation commit; the post-commit check and resulting package identity are recorded separately in the archive rather than attributed to this version-139 run.