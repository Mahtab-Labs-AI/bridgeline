# Contributing to Bridgeline

Thanks for considering it. This is a small project, so the bar is low: a clean build, passing
tests, and a clear description of what changed and why.

## Before you start

For anything bigger than a small fix, open an issue first (or comment on an existing one) so we
can agree on the approach before you put time into it. For a small fix or a clear bug, a pull
request is fine on its own.

Good places to start:
- Issues labeled [`good first issue`](https://github.com/Mahtab-Labs-AI/bridgeline/labels/good%20first%20issue).
- Issues labeled [`help wanted`](https://github.com/Mahtab-Labs-AI/bridgeline/labels/help%20wanted).
- The "Open items" list in `HANDOFF.md` and the design decisions in
  `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`.

## Project layout

| Module | What |
|---|---|
| `:proxy-core` | Pure Kotlin/JVM: the HTTP proxy, client registry, PAC/mobileconfig, destination filter, NIO tunnel server. No Android dependency; see "`:proxy-core` rules" in `README.md` before touching it. |
| `:app` | The Android app: Compose UI, the foreground service, Wi-Fi Direct and USB, Settings, Diagnostics. |

## Setup and build

See "Build and test" and "Environment setup on a new Windows machine" in `README.md` / `HANDOFF.md`.
In short:

```powershell
. .\scripts\env.ps1
.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The project keeps **zero Kotlin compiler warnings** and a stable lint baseline. CI (GitHub Actions)
fails the build on any new `w:` line, so check your local output before pushing.

## Making a change

1. Fork the repo, branch off `main`.
2. Write the test first where the code is pure and testable on the JVM (most of `:proxy-core` and
   the app's non-UI logic). This codebase leans heavily on unit tests over manual verification;
   several bugs were caught only because a test could simulate a race or a timing edge case that's
   hard to hit by hand.
3. Keep existing guarantees intact — several files have comments explaining concurrency rules
   (serialized session lifecycle, lock ordering, "revoke only through `ProxyServer.revoke`", and
   so on). If you're touching one of those files, read the comment above the code first.
4. Run the full build and test command above. Zero warnings, no new lint findings.
5. Open a pull request against `main` with a clear description of what changed and why. CI must
   pass (build, tests, lint) before it can merge.

## Reporting bugs

Open an issue with: what you did, what you expected, what happened instead, your Android version
and device (or "emulator"), and — if it's proxy-related — whatever the Diagnostics screen's
"Verbose logging" export shows (it never contains website names or your Wi-Fi password, so it's
safe to paste).

## Reporting a security issue

Please don't open a public issue for a security vulnerability. Use GitHub's private
vulnerability reporting instead: **Security → Report a vulnerability** on this repo.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
