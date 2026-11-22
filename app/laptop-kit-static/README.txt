Bridgeline: Laptop kit
========================

This folder lets an offline Windows laptop share this phone's cellular data
over a USB cable, with nothing already installed on the laptop (design spec
docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md, section 8a,
"Offline laptops: the Laptop kit").

What's in here
--------------
- platform-tools\         Android's adb.exe and its supporting files, fetched
                           and verified by the phone from Google's own SDK
                           repository. Nothing here was redistributed by this
                           app - your phone downloaded it directly from Google.
- connect-usb.ps1          The actual sharing script.
- connect-usb.cmd          A double-click launcher for connect-usb.ps1.
- README.txt               This file.

How to use it
-------------
1. Plug the phone into this laptop with a USB cable.
2. On the phone, choose "File transfer" (MTP) for the USB connection, if asked.
3. Copy this whole "Bridgeline-LaptopKit" folder from the phone to anywhere on
   the laptop (the Desktop is fine).
4. On the phone, turn on USB debugging (Settings > Developer options) and
   accept the "Allow USB debugging?" prompt when it appears.
5. On the phone, start "USB sharing" in the Bridgeline app.
6. On the laptop, double-click connect-usb.cmd in the copied folder. It finds
   the bundled adb.exe automatically, and points this laptop's system proxy at
   the phone for the rest of the session. Press Enter in its window when
   you're done, and it puts your proxy settings back exactly as they were.

Driver note
-----------
Windows normally binds its own built-in WinUSB driver to a Pixel's ADB
interface automatically, and nothing further is needed. If "adb devices" (or
connect-usb.cmd) doesn't see the phone at all, install the Google USB Driver
from Android's SDK Platform Tools page and try again - that one extra driver
install is outside what this kit can do for you automatically.
