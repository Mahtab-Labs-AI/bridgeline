# Bridgeline: Warn When USB Sharing Leaves No Wi-Fi Seat

Date: 2026-11-25
Status: Draft for approval; review corrections applied
Related issue: https://github.com/Mahtab-Labs-AI/bridgeline/issues/11
Source baseline: `e6af3c1d6214a00c3565e68278fe9dd09fd6d926` (`develop`)

## Intent and decisions

On a fresh install, `usbSharingEnabled` defaults to `true` and `maxClients` defaults to `1`. Per the approved design (spec §12, decision D7), the USB laptop's slot is pinned and permanently reserved whenever USB sharing is on, whether or not a laptop is actually connected. With `maxClients = 1`, that pinned seat consumes the entire registry, so a Wi-Fi guest can never be admitted: every connection, including its first `/proxy.pac` fetch, is refused with `REGISTRY_FULL` (403). This is exactly the condition that produced a real "no internet" symptom during this investigation (see `artifacts/README.md`'s existing "disable USB sharing" caveat, added after the fact).

Nothing in the app currently tells the user this is happening. `SessionController.kt` already computes the configured capacity available to Wi-Fi after the USB reservation (`wifiSeats = maxClients - (if usbEnabled 1 else 0)`), but that value is never surfaced. This is total Wi-Fi capacity, not the number of currently unoccupied seats; ordinary occupancy must not trigger this warning.

This fix is a real app behavior/UI change, not a diagnostics tool — it lands on a feature branch of the main checkout (`C:\source\bridgeline`, branch `develop`), not the diagnostics worktree.

**Not in scope:** changing any default value, changing admission/registry logic, blocking a settings change, or adding a confirmation dialog. This is purely informational.

## Approaches considered

1. **Confirmation dialog on the triggering settings change:** catches the mistake at the moment it's made, but adds a blocking interaction and changes how Settings behaves; also awkward for a user who deliberately wants USB-only sharing.
2. **Change the default `maxClients` to 2:** reduces how often this is hit for new installs, but doesn't explain anything, and a user can still recreate the exact same zero-seat condition by setting `maxClients` back to 1.
3. **Passive, informational warning in both existing warning surfaces (selected):** mirrors the app's own established pattern for this kind of non-fatal, fixable gotcha — the existing VPN warning (`HomeScreen.kt`'s `VpnWarning()`, documented in spec §12.9 as "a Setup check plus a Home banner"). No behavior change, no new interaction model, consistent with how the app already handles a structurally identical problem (VPN swallowing replies).

Approach 3 is selected.

## Where the condition lives

Home describes the **active session**. Settings previews the **edited configuration**. These values may differ until sharing restarts.

`SessionUi` (`SessionRepository.kt`) gains one new field:

```kotlin
val activeWifiSeatCapacity: Int? = null,
```

Add a read-only `SessionController.wifiSeatCapacity: Int?` getter derived from one local snapshot of its existing immutable `sessionConfig`. Return null when that snapshot is absent; otherwise compute `cfg.maxClients.coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT) - (if (cfg.usbEnabled) 1 else 0)`, matching the running registry's startup configuration. Do not re-read preferences, call `config()`, mutate the registry, or derive capacity by subtracting all occupied slots. Existing teardown clears `sessionConfig`, so a completed stop or failed-start cleanup returns the getter to null.

`ServiceSessionRepository.buildUi` maps that getter to `activeWifiSeatCapacity`, leaving it null when no controller/configuration exists. Keep the existing meaning of `SessionUi.maxClients` unchanged: it currently reflects saved settings and must not drive this Home warning. No new preference-derived USB field is needed on `SessionUi` for this change.

`FakeSessionRepository` must emit a coherent active capacity for its simulated session, snapshotting the simulated USB/limit inputs at start and clearing capacity on stop. Provide a test seam for constructing zero-capacity sessions. Editing or saving next-start settings must not silently change the simulated active capacity.

Settings continues to compute `usbSharingEnabled && maxClients <= 1` from its existing local Compose state. Moving the slider updates this preview immediately. The USB switch already persists its preference immediately while idle; it is locked during a real session. The two Settings controls retain those existing save semantics.

Required state transitions:

| Active configuration | Edited/saved next-start configuration | Home behavior before restart | After restart |
| --- | --- | --- | --- |
| USB on, limit 1 | USB on, limit 2 or 3 | Keep warning; active Wi-Fi capacity is still zero | Hide warning |
| USB on, limit 2 or 3 | USB on, limit 1 | Keep warning hidden; active Wi-Fi capacity is still positive | Show warning |
| USB on, limit 1 | USB turned off after stopping | No warning while idle | Hide warning with USB off |

Unsaved or rejected edits never change Home's active-session warning.

## Home banner

A new private composable in `HomeScreen.kt`, `ZeroWifiSeatWarning()`, styled identically to the existing `VpnWarning()` (same `Card` with `errorContainer`/`onErrorContainer` colors, same `Warning` icon, same `Row`/padding layout) — copy its structure, change only the text.

Shown directly after the existing VPN-warning block, under the same gating the VPN warning already uses:

```kotlin
if (session.activeWifiSeatCapacity == 0 && session.phase != SessionPhase.IDLE) {
    ZeroWifiSeatWarning()
}
```

Text: *"USB sharing reserves the only client seat in this session. For Wi-Fi devices, set Max clients to 2 or 3, save, and restart sharing; or stop sharing and turn off USB sharing."*

The warning remains present if a higher limit has been saved but sharing has not restarted. A null capacity is unknown/inactive, not zero, and must not show the warning.

## Settings hints

`SettingsScreen.kt` already holds `maxClients` and `usbSharingEnabled` as local Compose state, and already has two conditional-helper-text sites that this reuses rather than duplicates:

1. **USB switch helper text** (existing `when` block, ~line 188): add a new first branch, checked before `demoSwitchLocked` and `usbNeedsFreeSlot`:
   ```kotlin
   usbSharingEnabled && maxClients <= 1 -> if (demoSwitchLocked) {
       "With this limit, USB sharing reserves the only client seat. Raise Max clients, save, and restart sharing; or stop sharing to turn USB sharing off."
   } else {
       "With this limit, USB sharing reserves the only client seat. Raise Max clients or turn USB sharing off."
   }
   ```
   This is mutually exclusive with `usbNeedsFreeSlot` (one requires `usbSharingEnabled`, the other requires `!usbSharingEnabled`), so it can never hide that branch. The new warning takes priority over the generic `demoSwitchLocked` message **but incorporates its stop/restart instructions**. The user must still understand why the switch is disabled and when a saved limit takes effect. When the preview no longer has zero capacity, the existing generic lock message returns. Use `MaterialTheme.colorScheme.error` when either this warning or `usbNeedsFreeSlot` applies; retain the existing neutral color for the generic lock note.

2. **Max clients slider helper text** (under the slider, ~line 380): show this preview warning when the edited configuration has zero capacity:
   ```kotlin
   if (usbSharingEnabled && maxClients <= 1) {
       Text(
           text = if (demoSwitchLocked) {
               "This limit leaves no Wi-Fi seat with USB sharing on. Raise the limit, save, and restart sharing; or stop sharing to turn USB sharing off."
           } else {
               "This limit leaves no Wi-Fi seat with USB sharing on. Raise the limit or turn USB sharing off."
           },
           style = MaterialTheme.typography.bodySmall,
           color = MaterialTheme.colorScheme.error,
       )
   }
   ```

   Use the existing generic "Applies the next time sharing starts" note only when the preview warning is absent and `demoSwitchLocked` is true; its information is already included in the locked warning. Keep `rejectionMessage` independent and visible when present.

Both hints describe the chosen configuration, rather than claiming it is already active. They update immediately from local Compose state. Home continues to describe the actual active capacity even when the preview changes or is saved.

## Testing

Use the existing **JVM JUnit 5/coroutines test infrastructure plus recorded manual Compose UI checks**. The repository currently has no Compose UI test dependencies, instrumentation runner, `androidTest` source set, or Robolectric setup. Adding those is outside this small change; do not claim that `testDebugUnitTest` runs Compose UI tests.

### Automated regression tests

- Extend `SessionControllerTest` to verify capacity is null before start and after completed stop/failed-start cleanup; zero for USB on with limit 1; positive for USB on with limit 2/3 and for USB off with limit 1. Include both open admission and code mode: saved-device occupancy must not redefine capacity.
- Change the mutable config source while a session runs and verify its exposed capacity stays unchanged in both directions (1 to 2/3 and 2/3 to 1). Stop/start and verify the new configuration is then reflected. Exercise RUNNING and DEGRADED states using the existing controller fakes.
- Extend `FakeSessionRepositoryTest` for the same active-versus-next-start distinction and capacity reset, using an explicit simulated USB input rather than Android preferences.
- Retain existing `SettingsSaverTest` coverage that changing only the limit does not restart sharing. Add focused coverage only if the new state projection requires it.

### Manual UI acceptance checks

Record the build, device/emulator model, and Android API used. Verify:

1. USB on + limit 1 shows both Settings hints. Increasing the slider to 2/3 removes the preview warnings immediately; moving it back restores them. Turning USB off while idle removes them.
2. Start USB on + limit 1: Home shows the warning. While sharing, the USB switch remains disabled and its warning explicitly says to stop sharing to change it. The slider warning explains save/restart. Verify warning colors and simultaneous VPN-banner layout.
3. Increase the limit to 2 while running: Settings previews the new value and retains the generic next-start note. Before and after Save, Home still warns until stop/start. After restart it no longer warns.
4. Start USB on + limit 2 with no saved Wi-Fi devices, then save limit 1: Home stays clear until restart, then warns. Use this setup so existing D7 save restrictions do not prevent the test.
5. Unsaved edits, rejected saves, and loading/unreadable settings must not replace active capacity with the preview. Warning-only changes must not alter existing validation or enable disabled controls. Any save rejection remains visible alongside the relevant hint.
6. Home hides the warning for IDLE, null capacity, and positive capacity, including when ordinary Wi-Fi seats are occupied. Check startup, degraded operation, stop, and restart for stale warnings.

No `proxy-core` implementation or tests change: admission, registry mutation, filtering, and tunnel behavior stay untouched. The only controller addition is a read-only view of the existing startup configuration.

Run the full documented delivery checks from `C:\source\bridgeline`, after loading `scripts\env.ps1`:

```powershell
.\gradlew.bat :proxy-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Report automated results and the manual acceptance results separately; if hardware checks cannot run, identify those checks as unverified.

## Builder decisions

The implementer may adjust exact composable/variable names and minor text wording, while preserving these requirements: Home describes active capacity; Settings describes the edited configuration; messages explain the USB reservation and the fix; locked controls retain stop instructions; saved limit changes retain restart instructions. The warning must never add a block to saving a setting or starting a session. Existing validation and control locking remain in force.

## Review corrections incorporated

1. Replaced the preference/saved-limit Home condition with read-only active capacity from the controller's startup configuration.
2. Preserved stop/restart guidance inside the higher-priority warning and distinguished preview messages from the active Home warning.
3. Replaced unsupported Compose UI test requirements with existing JVM regression tests, an explicit manual UI matrix, and the full build/test/lint delivery command.
