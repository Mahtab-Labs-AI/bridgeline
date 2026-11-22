## What this changes

<!-- One or two sentences: what, and why. Link the issue it fixes/closes, if any. -->

## How it was tested

<!-- Test names/output for new or changed behavior. If it's UI or device-only, say what you
     checked by hand and on what device/emulator. -->

## Checklist

- [ ] `:proxy-core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passes locally
- [ ] No new Kotlin compiler warnings (`w:` lines) and no new lint findings
- [ ] Existing concurrency/ordering guarantees in touched files are still intact (see the
      comments above the code, or `CONTRIBUTING.md`)
- [ ] No website names, hostnames, URLs, tokens or passwords added to any log line
