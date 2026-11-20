# USB client: `connect-usb.ps1`

Laptop-side tool for the "USB sharing" feature (see
`docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` §8a). The
phone shares its cellular data with this laptop over a USB cable, via
`adb forward` plus the app's loopback proxy listener on port 8081. No Wi-Fi,
no hotspot, no carrier tethering entitlement check.

```
Laptop app → laptop 127.0.0.1:8081 ──adb forward (USB)──► phone 127.0.0.1:8081 → USB ProxyServer → cellular
```

## Windows usage

Prerequisites:

- Android platform-tools (`adb`) installed somewhere the script can find it
  (see `-AdbPath` below).
- The phone connected by USB, with USB debugging enabled and the USB
  debugging prompt accepted (`adb devices` should show it as `device`, not
  `unauthorized`).
- "USB sharing" started in the app on the phone. The script will tell you if
  it isn't.

Run it from PowerShell (Windows PowerShell 5.1 or PowerShell 7 both work):

```powershell
.\connect-usb.ps1
```

Useful parameters:

| Parameter   | Default                                   | Purpose |
|-------------|--------------------------------------------|---------|
| `-AdbPath`  | auto-detected                              | Path to `adb.exe`. If omitted, the script checks `$env:ANDROID_HOME\platform-tools`, then `E:\Source\Android\sdk\platform-tools`, then your `PATH`. |
| `-Port`     | `8081`                                      | TCP port used on both ends of the forward and for the proxy. |
| `-Serial`   | (none)                                      | adb device serial. Required if more than one device is attached. |
| `-DryRun`   | off                                         | Prints every step it would take; makes no changes (no forward, no registry writes). |

Examples:

```powershell
.\connect-usb.ps1 -Serial emulator-5554 -DryRun
.\connect-usb.ps1 -Serial R5CT1234ABC -Port 8081
.\connect-usb.ps1 -AdbPath 'C:\Android\platform-tools\adb.exe'
```

### What it does

1. Locates `adb` and confirms exactly one authorized device is attached
   (or the one you named with `-Serial`).
2. Runs `adb forward tcp:<Port> tcp:<Port>`, so `127.0.0.1:<Port>` on the
   laptop reaches `127.0.0.1:<Port>` on the phone.
3. Sends a plain HTTP request to `http://127.0.0.1:<Port>/` through that
   forward, bypassing any proxy, with a short timeout. Any HTTP response
   (even an error page) counts as "the app is listening". If nothing
   answers, it prints **"Start sharing in the app first"**, removes the
   forward, and exits — it never touches your proxy settings in this case.
4. Saves your current Windows proxy configuration from
   `HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings`
   (`ProxyEnable`, `ProxyServer`, `ProxyOverride`, `AutoConfigURL`), noting
   which of those were not set at all.
5. Sets `ProxyEnable=1`, `ProxyServer=127.0.0.1:<Port>`,
   `ProxyOverride=<local>`, removes `AutoConfigURL`, and calls
   `InternetSetOption` (`INTERNET_OPTION_SETTINGS_CHANGED` and
   `INTERNET_OPTION_REFRESH`) so running WinINet-based apps (most browsers,
   .NET `HttpClient` by default, etc.) pick up the change without a restart.
6. Prints the active proxy and a hint for WinHTTP-based apps (see
   Troubleshooting), then waits for you to press Enter.
7. On Enter **or Ctrl+C**, it always restores your saved values exactly —
   deleting any setting that wasn't present before — notifies WinINet again,
   and runs `adb forward --remove tcp:<Port>`.

Nothing is left changed if you stop the script at any point: the restore
and forward-removal both run from a `finally` block.

### What it never does

- It never touches the registry unless step 3's probe succeeds.
- `-DryRun` never runs `adb forward`, never probes, and never reads or
  writes the registry — it only prints what it would do.
- It never runs `netsh winhttp import proxy source=ie` itself (that needs
  admin rights); it only prints the command as a hint.

## macOS / Linux: manual steps

There's no script for these platforms yet. Do it by hand:

1. Forward the port:

   ```sh
   adb forward tcp:8081 tcp:8081
   ```

   (add `-s <serial>` if more than one device is attached.)

2. Point your system proxy at `127.0.0.1:8081` for both HTTP and HTTPS, with
   a bypass for local addresses.

   **macOS** (replace `Wi-Fi` with your active network service, see
   `networksetup -listallnetworkservices`):

   ```sh
   networksetup -setwebproxy "Wi-Fi" 127.0.0.1 8081
   networksetup -setsecurewebproxy "Wi-Fi" 127.0.0.1 8081
   networksetup -setproxybypassdomains "Wi-Fi" "<local>" "127.0.0.1" "localhost"
   ```

   To undo:

   ```sh
   networksetup -setwebproxystate "Wi-Fi" off
   networksetup -setsecurewebproxystate "Wi-Fi" off
   ```

   **Linux (GNOME)**:

   ```sh
   gsettings set org.gnome.system.proxy mode 'manual'
   gsettings set org.gnome.system.proxy.http host '127.0.0.1'
   gsettings set org.gnome.system.proxy.http port 8081
   gsettings set org.gnome.system.proxy.https host '127.0.0.1'
   gsettings set org.gnome.system.proxy.https port 8081
   ```

   To undo:

   ```sh
   gsettings set org.gnome.system.proxy mode 'none'
   ```

   Other desktop environments and distros without GNOME's proxy module can
   set the same `http_proxy`/`https_proxy` environment variables instead,
   or configure the proxy per-application.

3. When you're done, remove the forward:

   ```sh
   adb forward --remove tcp:8081
   ```

## Troubleshooting

- **Device shows as `unauthorized` in `adb devices`.** Unlock the phone and
  accept the "Allow USB debugging?" prompt, then re-run the script.

- **"No authorized device attached" / "Multiple devices attached".** Run
  `adb devices` yourself to see what's connected, and pass `-Serial` with
  the exact serial you want.

- **"Nothing is listening on 127.0.0.1:<Port>".** The phone-side proxy
  listener isn't up yet. Open the app, turn off Demo mode if needed, and
  press Start on the Home screen before re-running the script. This is also
  what you'll see while the app's USB listener hasn't been built yet in a
  development build — it's expected, and the script exits cleanly without
  changing anything.

- **Some apps still bypass the proxy.** The system proxy set here covers
  apps that honor Windows' WinINet proxy settings (most browsers, many
  .NET apps). Apps that use WinHTTP directly may need:

  ```
  netsh winhttp import proxy source=ie
  ```

  run once from an elevated (admin) prompt — the script prints this as a
  hint but does not run it for you. Apps with their own network stack
  (some games, custom Electron/Flutter builds, VPN clients, anything
  talking raw UDP) may ignore the system proxy entirely regardless; that's
  a limitation of the system-proxy approach, not of this script.

- **Forward already exists / port in use.** If a previous run of this
  script didn't clean up (e.g. the process was killed rather than
  Ctrl+C'd), run `adb forward --remove tcp:8081` manually before retrying.
