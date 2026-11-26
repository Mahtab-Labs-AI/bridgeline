# USB Seat Wi-Fi Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Explain when USB sharing reserves the only client seat, accurately distinguishing the current session from edited next-start settings.

**Architecture:** Expose a nullable, read-only Wi-Fi capacity from the controller's startup snapshot and project it into `SessionUi`. Home uses that active capacity; Settings uses its existing local state and preserves locked-switch and restart guidance. Keep admission behavior unchanged.

**Tech Stack:** Kotlin, Compose Material 3, StateFlow, JUnit 5, kotlinx-coroutines-test; existing Android Gradle build and manual device UI checks.

**Spec:** `docs/superpowers/specs/2026-11-25-usb-seat-wifi-warning-design.md` (read alongside this plan).

**Execution state:** Task 1 implemented with RED/GREEN controller and fake regression evidence; Task 2 pending. See the archive implementation evidence ledger.

## Global Constraints

- Source baseline: `e6af3c1d6214a00c3565e68278fe9dd09fd6d926` (`develop`).
- Home describes the **active session**. Settings previews the **edited configuration**.
- `val activeWifiSeatCapacity: Int? = null`; null means unknown/inactive, never zero.
- Capacity is `cfg.maxClients.coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT) - (if (cfg.usbEnabled) 1 else 0)`, using one local `sessionConfig` snapshot.
- No changes to defaults, admission/registry logic, save restrictions, or control locking; no confirmation dialog. Preserve existing lifecycle serialization and lock ordering.
- No `proxy-core` implementation changes and no new Compose test, instrumentation, Robolectric, or formatting dependencies.
- JVM tests cover state behavior; recorded manual checks cover Compose presentation. Never label JVM test results as Compose UI tests.
- Preserve existing `SessionUi.maxClients` semantics and all original/diagnostics source isolation requirements. Stage explicit paths.

## Review Focus

1. Saved limits differ from active limits: the Home warning changes only after stop/start — Task 1 snapshot tests and Task 2 M3/M4.
2. Failed startup or completed teardown: capacity returns to null without a stale banner — Task 1 lifecycle assertions and Task 2 M6.
3. Occupied or inactive saved devices: capacity measures the USB reservation, not remaining free slots — Task 1 occupancy test and Task 2 M6.
4. Locked USB control plus zero-capacity preview: the warning still explains stop/restart — Task 2 M2/M3.
5. Unsaved/rejected edits and loading/unreadable settings: active state and existing validation remain intact — Task 1 rejected-save test and Task 2 M5.

---

## File Responsibilities

Paths below are relative to the isolated source worktree.

| File | Responsibility |
| --- | --- |
| `app/src/main/kotlin/com/wifiproxy/app/service/SessionController.kt` | Read-only `wifiSeatCapacity: Int?` getter |
| `app/src/main/kotlin/com/wifiproxy/app/session/SessionRepository.kt` | Nullable active-capacity field on `SessionUi` |
| `app/src/main/kotlin/com/wifiproxy/app/session/ServiceSessionRepository.kt` | Map controller capacity into UI state |
| `app/src/main/kotlin/com/wifiproxy/app/session/FakeSessionRepository.kt` | Snapshot simulated capacity at start; clear on stop |
| `app/src/test/kotlin/com/wifiproxy/app/service/SessionControllerTest.kt` | Real controller lifecycle, snapshot, and occupancy regressions |
| `app/src/test/kotlin/com/wifiproxy/app/session/FakeSessionRepositoryTest.kt` | Demo snapshot and rejected-save regressions |
| `app/src/main/kotlin/com/wifiproxy/app/home/HomeScreen.kt` | Active-session warning card |
| `app/src/main/kotlin/com/wifiproxy/app/settings/SettingsScreen.kt` | Edited-configuration hints, priority, and color |
| `docs/verification/2026-11-25-usb-seat-wifi-warning.md` | Automated results and M1–M6 manual acceptance evidence |

### Task 1: Expose and project active Wi-Fi capacity

**Files:** Modify the first four production files and both test files listed above. Copy this plan and its spec into the source worktree in Step 1. Existing `SettingsSaverTest.kt` provides the no-restart-on-limit-save regression and needs no change unless a new gap is found.

**Interfaces:**
- Consumes: `SessionController.sessionConfig: SessionConfig?`, `SessionConfig.usbEnabled: Boolean`, `SessionConfig.maxClients: Int`, existing lifecycle and fake clock facilities.
- Produces: `SessionController.wifiSeatCapacity: Int?` and `SessionUi.activeWifiSeatCapacity: Int? = null`.
- Demo seam: append `private val usbEnabled: () -> Boolean = { false }` to `FakeSessionRepository`'s constructor. This plan chooses false to preserve existing demo behavior and existing callers; tests explicitly supply USB state. No Android preference dependency is added.
- Controller test seam: append `configSource: (() -> SessionConfig)? = null` to the existing private `controller(...)` test helper, passing `configSource ?: { config }` to the constructor. Existing calls remain unchanged.

- [x] **Step 1: Establish the isolated checkout and preserve the design.** Use `superpowers:using-git-worktrees` at execution time. Create branch `feature/usb-seat-wifi-warning` from the stated `develop` baseline in `C:\source\bridgeline-usb-seat-warning`, a sibling of `bridgeline`, not inside the archive or diagnostics checkout. If the path/branch already exists, inspect it before reuse; never reset another checkout. Verify the baseline and clean status, and account for unexpected drift before proceeding. Copy this plan and the revised spec from the handoff archive into their matching `docs/superpowers/` paths there. All remaining commands run in that source worktree after `. .\scripts\env.ps1`.

- [x] **Step 2: Add failing controller regression tests.** Use the existing `runTest`, `backgroundScope`, `FakeSessionEgress`, `FakeSessionServer`, `MemorySlots`, `BOTH`, `WIFI_ONLY`, `storedSlots`, and `runCurrent` facilities. Add the optional test-helper callback above. Define these cases and assertions:

```kotlin
// `wifi seat capacity follows the effective startup configuration`
// Start/stop each case; assert null before start and after stop.
// Input triples: usbEnabled, maxClients, expected capacity.
listOf(Triple(true, 1, 0), Triple(true, 2, 1), Triple(true, 3, 2),
       Triple(false, 1, 1), Triple(true, 0, 0), Triple(true, 4, 2))
// Repeat valid cases for requireDeviceCodes = false and true.
assertEquals(expected, c.wifiSeatCapacity)
assertEquals(expected, c.registry!!.maxClients - if (usbEnabled) 1 else 0)

// `wifi seat capacity stays with the active config until restart`
// For nextLimit in listOf(2, 3), run both 1 -> nextLimit and nextLimit -> 1.
// Supply mutable configSource and a FakeLink; start, runCurrent(), link.up(), runCurrent().
assertEquals(SessionPhase.RUNNING, c.state.value)
// Change configSource's returned config while RUNNING, then degrade via egress.
assertEquals(initialCapacity, c.wifiSeatCapacity) // RUNNING and DEGRADED
// stop(); runCurrent(); start(); runCurrent()
assertEquals(nextCapacity, c.wifiSeatCapacity)

// `occupied and inactive wifi slots do not reduce configured capacity`
// BOTH(limit 3), MemorySlots(storedSlots(2)); run in both admission modes.
// In open mode occupy both available auto seats using the existing registry fixture.
assertEquals(2, c.wifiSeatCapacity) // before and after occupancy
```

Also add `assertNull(c.wifiSeatCapacity)` after cleanup in the existing failed-server-start rollback test. Use `backgroundScope` for indefinite controller loops; do not use `advanceUntilIdle()` on them.

- [x] **Step 3: Confirm the controller tests fail.** Run `.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.service.SessionControllerTest' --max-workers=1`. Expected initial failure: unresolved `wifiSeatCapacity`; distinguish this intended failure from an environment/build failure.

- [x] **Step 4: Implement the real state projection.** Add the getter beside existing `openAdmission`, reading `val cfg = sessionConfig ?: return null` once and using the exact formula in Global Constraints. Append the nullable UI field with KDoc distinguishing active capacity from saved `maxClients`. In `ServiceSessionRepository.buildUi`, assign `activeWifiSeatCapacity = controller?.wifiSeatCapacity`. Do not add preference observers, change the ticker, call `config()`, or mutate registry limits.

- [x] **Step 5: Run the controller tests.** Run the Step 3 command again. Expected: all existing and added controller tests pass with no new compiler warnings.

- [x] **Step 6: Add failing fake-repository tests.** Before limit-1 cases, revoke both seeded demo slots; otherwise the existing fake rejects a lower limit because its seed contains two devices. Use `backgroundScope` and virtual-time advances of 1,500 ms for start and 500 ms for stop. Add `active capacity snapshots demo inputs until restart`, `rejected demo settings leave active capacity unchanged`, and `demo capacity survives degradation and clears on stop` with these assertions:

```kotlin
// Snapshot test: callback initially true; limit initially 1 after revoking seeds.
assertNull(repo.session.value.activeWifiSeatCapacity) // idle
assertEquals(0, repo.session.value.activeWifiSeatCapacity) // started
assertEquals(SettingsUpdate.SAVED, repo.updateSettings(repo.settings.value.copy(maxClients = 2)))
assertEquals(0, repo.session.value.activeWifiSeatCapacity) // still active at limit 1
assertEquals(2, repo.session.value.maxClients) // existing saved-value semantics
// After stop, capacity null; after start, capacity 1.
// Change callback to false while running: capacity stays 1, then becomes 2 after restart.
// For the reverse limit case, use a fresh repo with USB callback true, revoked seeds,
// and saved limit 2. After start, saving limit 1 keeps capacity 1 until restart, then 0.
// Close each repo when its scenario ends so its simulated loop cannot affect later cases.

// Rejected-save test: default two demo slots, USB callback true, start at limit 3.
assertEquals(SettingsUpdate.REJECTED, repo.updateSettings(repo.settings.value.copy(maxClients = 1)))
assertEquals(2, repo.session.value.activeWifiSeatCapacity)
// Assert saved settings also remain unchanged.

// Lifecycle test: assert unchanged capacity during the existing 30-tick DEGRADED
// interval and recovery; assert null once stop reaches IDLE.
```

- [x] **Step 7: Confirm the fake tests fail.** Run `.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.session.FakeSessionRepositoryTest' --max-workers=1`. Expected: initial compile failure for the absent USB callback, or assertion failure for missing capacity behavior.

- [x] **Step 8: Implement the simulated snapshot.** Append the callback parameter specified in Interfaces. When `start()` transitions from IDLE to STARTING, capture `_settings.value.maxClients` and `usbEnabled()` once and publish the computed capacity. Preserve it through RUNNING, DEGRADED, STOPPING, and `updateSettings`; clear it when stop reaches IDLE. Leave seed data, preference persistence, counter/timing behavior, and existing fake save/admission behavior unchanged.

- [x] **Step 9: Verify state tests together.** Run `.\gradlew.bat :app:testDebugUnitTest --tests 'com.wifiproxy.app.service.SessionControllerTest' --tests 'com.wifiproxy.app.session.FakeSessionRepositoryTest' --tests 'com.wifiproxy.app.session.SettingsSaverTest' --max-workers=1`. Expected: all pass, including the existing test proving limit-only saves do not restart real sharing. Review that both real and fake projections preserve active capacity while next-start settings change.

- [x] **Step 10: Commit the state change.** Stage the six explicit implementation/test paths from this task plus the copied spec and plan; commit as `feat(app): expose active Wi-Fi seat capacity`. Do not stage unrelated files, generated outputs, credentials, or local Android configuration.

### Task 2: Display actionable warnings and verify the feature

**Files:** Modify `HomeScreen.kt` and `SettingsScreen.kt`; create `docs/verification/2026-11-25-usb-seat-wifi-warning.md`.

**Interfaces:**
- Consumes: Task 1's `SessionUi.activeWifiSeatCapacity: Int?`, existing `SessionPhase`, Settings local `usbSharingEnabled`, `maxClients`, `demoSwitchLocked`, and `usbNeedsFreeSlot`.
- Produces: private `@Composable fun ZeroWifiSeatWarning()` in `HomeScreen.kt`, updated inline Settings hint branches, and recorded M1–M6 acceptance evidence. No new shared UI model/helper or test dependency is required.

- [ ] **Step 1: Establish the missing-warning reproduction.** Build the Task 1 APK with `.\gradlew.bat :app:assembleDebug --max-workers=1`. On a test device with no saved Wi-Fi devices, select USB on and limit 1, start sharing, and record the absent Home/Settings warnings as the pre-change manual failure. Record device model/API and build revision. If no device/emulator is available, mark this and later manual checks unverified; do not fabricate results or introduce UI-test dependencies.

- [ ] **Step 2: Add the Home warning.** Copy the existing `VpnWarning()` card structure, colors, icon, spacing, and typography into `ZeroWifiSeatWarning()`. Insert it immediately after the existing VPN-warning block with `session.activeWifiSeatCapacity == 0 && session.phase != SessionPhase.IDLE`. Use the spec's exact Home message, including save/restart and stop-before-disabling-USB instructions. Never use `session.maxClients` to decide this warning.

- [ ] **Step 3: Update the USB switch hint.** In the existing text `when`, put the local zero-capacity condition first. Use the spec's separate locked/unlocked messages. When false, retain existing locked, `usbNeedsFreeSlot`, and default messages in their existing order. Use error color when the zero-capacity preview or `usbNeedsFreeSlot` applies, otherwise the existing neutral color. Preserve the switch's enablement expression and immediate preference write.

- [ ] **Step 4: Update the slider hint.** For the same local zero-capacity preview, show the spec's locked/unlocked slider text in error color. Otherwise show the existing generic next-start note when locked. Keep save rejection text independent; do not change slider bounds, Save behavior, or validation. Higher preview limits can remove Settings warnings while Home correctly keeps its active-session warning.

- [ ] **Step 5: Build and perform M1–M6 checks.** Rebuild the debug APK and install the built artifact on the test device if available. Run each numbered manual check in the spec's Testing section, recording it as M1–M6 in the verification document. In particular, explicitly record Home before and after Save, then after restart for both limit directions; locked switch explanation and error colors; simultaneous VPN banner; rejected-save visibility; unknown/loading/idle state; and unchanged positive capacity with occupied slots. Use a real session for the saved-versus-active tests; the fake's other pre-existing demo behavior is not evidence about the real controller. Record screenshots for the Home warning, locked USB hint, and pending higher-limit state, without passwords or client details.

- [ ] **Step 6: Run final automated checks.** After `. .\scripts\env.ps1`, run `.\gradlew.bat :proxy-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1`. Expected: exit 0, no Kotlin compiler warnings, and no new lint findings. Existing lint findings must be distinguished from new ones. Keep build execution serial; if the previous machine memory limits are needed, use the established low-memory test/build configuration and record it. Do not alter global Gradle/JDK settings or tracked build files merely to tune local memory.

- [ ] **Step 7: Record delivery evidence.** In the verification document, list the executed commands and results, device/build details, M1–M6 outcomes, UI evidence paths, and any unverified checks. Confirm the source diff touches only planned app/docs paths, with no proxy-core behavior changes and no new dependencies. Update the plan checkboxes to reflect completed work; checks not run remain unchecked. Request whole-branch review before integration using `superpowers:requesting-code-review`.

- [ ] **Step 8: Commit and hand off.** Stage only `HomeScreen.kt`, `SettingsScreen.kt`, the verification document/evidence, and the updated plan; commit as `feat(app): warn when USB reserves the only client seat`. Preserve the isolated branch for review. Do not merge, publish, or change the original/diagnostics checkout as part of this plan. Resolve material review findings and rerun affected checks before claiming completion.
