@echo off
rem Laptop kit launcher for android-wifi-proxy USB sharing (design spec section 8a).
rem Forwards all arguments (e.g. -DryRun, -Serial emulator-5554) through to connect-usb.ps1,
rem which sits in this same folder and checks its bundled platform-tools folder for adb.exe first.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect-usb.ps1" %*
