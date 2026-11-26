# USB seat Wi-Fi warning: implementation verification

Date: 2026-11-26. Automated implementation evidence only; independent review and device acceptance are pending.

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
