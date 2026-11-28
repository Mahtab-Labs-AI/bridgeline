# Device checklist: USB seat Wi-Fi warning (M2-M6)

Fast, tap-by-tap version of the M1-M6 matrix in
`C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\03-return-for-verification.md`,
for executing on the Pixel 11 (or another test device) in one sitting. M1 already passed by user report
(see `2026-11-25-usb-seat-wifi-warning.md` in this same folder). This covers M2-M6.

## Install

- APK: `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\bridgeline-usb-seat-warning-00ef04b.apk`
- SHA-256: `A742BDA60588ADA8B5436B8C5EB78AB4C5D92C3E4154D43804077824EBBAA9B5`
- Version: `1.0.0.139` (code `139`), application id `com.wifiproxy.app`
- `adb install -r bridgeline-usb-seat-warning-00ef04b.apk`

Record once: device model ________, Android API ________, package/version shown in Settings ________.

## M2 - active Home warning + locked Settings + VPN coexistence

1. Settings: turn USB sharing on, drag **Max clients** to 1, Save.
2. Home: tap Start, wait for RUNNING.
3. On Home, confirm a red card with a warning icon reads: *"USB sharing reserves the only client seat
   in this session. For Wi-Fi devices, set Max clients to 2 or 3, save, and restart sharing; or stop
   sharing and turn off USB sharing."*
4. Open Settings while still RUNNING: confirm the Max clients hint reads *"With this limit, USB sharing
   reserves the only client seat. Raise Max clients, save, and restart sharing; or stop sharing to turn
   USB sharing off."* (the save-and-restart variant, not the generic one).
5. If a VPN is available, turn it on: confirm the VPN warning card and the zero-seat warning card both
   show on Home at once, neither clipped nor overlapping.
6. Screenshot Home (warning) and Settings (locked hint). Save under
   `C:\source\android-wifi-proxy-handoff\docs\handoffs\usb-seat-wifi-warning\evidence\device\`.

- [ ] Pass / Fail / Unverified: ____________________

## M3 - running limit 1 -> save limit 2 -> restart

1. Continue from M2 state (RUNNING, Max clients = 1, warning showing on Home).
2. Settings: drag slider to 2, Save. Confirm the save succeeds and the Settings-page warning hint clears.
3. Switch to Home without stopping sharing: confirm the warning card is **still showing** (session read
   maxClients=1 at start; a save alone doesn't change it).
4. Stop sharing, then Start again.
5. Home: confirm the warning is now **gone**.

- [ ] Pass / Fail / Unverified: ____________________

## M4 - running limit 2 -> save limit 1 -> restart (no saved Wi-Fi devices)

Precondition: no saved Wi-Fi client devices competing for the lowered limit. If any exist, revoke them
first (don't weaken validation or delete unrelated saved state to work around this).

1. Settings: Max clients = 2, USB on, Save if not already. Home: Start, confirm **no** warning (1 free
   Wi-Fi seat).
2. Settings: drag slider to 1, Save - should succeed (no competing saved devices).
3. Home (still running, no restart yet): confirm **still no** warning.
4. Stop, then Start again.
5. Home: confirm the warning **now appears**.

- [ ] Pass / Fail / Unverified: ____________________

## M5 - unsaved/rejected edits and unreadable settings preserve active state

1. RUNNING with Max clients = 2 (no warning). Open Settings, drag slider to 1, but do **not** tap Save.
   Confirm the preview hint appears in Settings, but Home (without saving) still shows no warning.
2. Force a rejected save: get active Wi-Fi slots >= the new limit (e.g. two connected test devices),
   try to save a lower limit. Confirm the rejection message appears (*"Revoke ... first, then lower the
   limit to N."*) and nothing about the active session or Home changes.
3. If you can reproduce an unreadable-credentials state, confirm Settings shows the
   credentials-unreadable message and edits are preserved rather than silently discarded. If not
   reproducible on this device, mark this row Unverified with the reason - don't force it.

- [ ] Pass / Fail / Unverified: ____________________

## M6 - idle/unknown/positive/occupied capacity never shows a stale warning

1. Stopped (IDLE): confirm no warning on Home regardless of Max clients/USB switch position.
2. Start with Max clients = 2 or 3 (USB on or off): confirm no warning (capacity > 0).
3. Connect a Wi-Fi client to occupy a seat: confirm occupancy alone does **not** produce the warning
   (it's driven by configured capacity at startup, not live occupancy).
4. Toggle cellular off/on (or otherwise force DEGRADED/recovery) while RUNNING: confirm the warning
   state doesn't flicker or appear/disappear incorrectly.
5. Stop sharing: confirm the warning clears immediately (IDLE always suppresses it), then Start again a
   couple of times with different configs to confirm nothing stale carries over from a prior run.

- [ ] Pass / Fail / Unverified: ____________________

## After testing

Update the M2-M6 rows in `2026-11-25-usb-seat-wifi-warning.md` (this same folder; currently all
"DEFERRED TO ORIGINATING SESSION") with the actual results and screenshot paths, and note device
model/API/package version there. Any failure goes back to implementation, not straight to a fix here.
This checklist does not authorize merge, release, or publication on its own.
