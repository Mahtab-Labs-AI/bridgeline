<#
.SYNOPSIS
    Shares an Android phone's cellular data with this Windows laptop over USB
    (adb forward), by pointing the Windows system proxy at the phone's local
    USB proxy listener for the duration of the session.

.DESCRIPTION
    This is the laptop side of the android-wifi-proxy "USB sharing" feature
    (see docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md, section 8a).

    It:
      1. Locates adb and finds exactly one attached, authorized device
         (or uses -Serial).
      2. Runs `adb forward tcp:<Port> tcp:<Port>` so the laptop can reach the
         phone's loopback proxy listener.
      3. Probes http://127.0.0.1:<Port>/ (bypassing any proxy) to confirm the
         app's listener is actually up. If nothing answers, it says so and
         exits cleanly without touching the Windows proxy configuration.
      4. Saves the current HKCU Internet Settings proxy values, then sets
         the system proxy to 127.0.0.1:<Port> with a "<local>" bypass list,
         and notifies WinINet so running apps pick up the change immediately.
      5. Waits for Enter.
      6. Always (including on Ctrl+C) restores the previous proxy values
         exactly as they were, notifies WinINet again, and removes the adb
         forward.

.PARAMETER AdbPath
    Path to adb.exe. If omitted, this script searches, in order:
      1. $PSScriptRoot\platform-tools\adb.exe (the bundled copy, present when this script was
         unpacked from the app's offline "Laptop kit" - see docs/superpowers/specs/
         2026-11-19-android-wifi-proxy-design.md section 8a)
      2. $env:ANDROID_HOME\platform-tools\adb.exe
      3. E:\Source\Android\sdk\platform-tools\adb.exe
      4. adb on PATH

.PARAMETER Port
    TCP port used on both the laptop and the phone. Default 8081.

.PARAMETER Serial
    adb device serial to use. Required if more than one device is attached.

.PARAMETER DryRun
    Prints every action this script would take, without changing anything:
    no adb forward is created, and the registry is never written.

.EXAMPLE
    .\connect-usb.ps1

.EXAMPLE
    .\connect-usb.ps1 -Serial emulator-5554 -DryRun

.EXAMPLE
    .\connect-usb.ps1 -AdbPath 'C:\Android\platform-tools\adb.exe' -Port 8081
#>
[CmdletBinding()]
param(
    [string]$AdbPath,
    [int]$Port = 8081,
    [string]$Serial,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ----------------------------------------------------------------------------
# Constants
# ----------------------------------------------------------------------------

$script:InternetSettingsKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$script:ProxySettingNames = @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL')

# WinINet option constants (wininet.h)
$script:INTERNET_OPTION_SETTINGS_CHANGED = 39
$script:INTERNET_OPTION_REFRESH = 37

# ----------------------------------------------------------------------------
# Console output helpers
# ----------------------------------------------------------------------------

function Write-Status {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host $Message -ForegroundColor Cyan
}

function Write-Success {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Write-WarnMsg {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host $Message -ForegroundColor Yellow
}

function Write-Err {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host $Message -ForegroundColor Red
}

function Write-Action {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "[DryRun] $Message" -ForegroundColor DarkGray
}

# ----------------------------------------------------------------------------
# WinINet P/Invoke
# ----------------------------------------------------------------------------

if (-not ([System.Management.Automation.PSTypeName]'WinProxyShare.WinInetNative').Type) {
    Add-Type -Namespace WinProxyShare -Name WinInetNative -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)]
public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);
'@
}

function Send-WinInetSettingsChanged {
    [void][WinProxyShare.WinInetNative]::InternetSetOption([IntPtr]::Zero, $script:INTERNET_OPTION_SETTINGS_CHANGED, [IntPtr]::Zero, 0)
    [void][WinProxyShare.WinInetNative]::InternetSetOption([IntPtr]::Zero, $script:INTERNET_OPTION_REFRESH, [IntPtr]::Zero, 0)
}

# ----------------------------------------------------------------------------
# adb helpers
# ----------------------------------------------------------------------------

function Resolve-AdbPath {
    param([string]$ProvidedPath)

    if ($ProvidedPath) {
        if (Test-Path -LiteralPath $ProvidedPath -PathType Leaf) {
            return (Resolve-Path -LiteralPath $ProvidedPath).Path
        }
        throw "adb not found at the path given via -AdbPath: $ProvidedPath"
    }

    $candidates = @()
    if ($PSScriptRoot) {
        $candidates += (Join-Path $PSScriptRoot 'platform-tools\adb.exe')
    }
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
    }
    $candidates += 'E:\Source\Android\sdk\platform-tools\adb.exe'

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $onPath = Get-Command -Name 'adb' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) {
        return $onPath.Source
    }

    throw "Could not locate adb. Checked -AdbPath, `$PSScriptRoot\platform-tools, `$env:ANDROID_HOME\platform-tools, E:\Source\Android\sdk\platform-tools, and PATH."
}

function Get-AdbDeviceLines {
    param([Parameter(Mandatory)][string]$AdbPath)

    $output = & $AdbPath devices 2>&1
    return $output | Where-Object {
        $_ -and
        ($_ -notmatch '^\s*List of devices attached') -and
        ($_ -notmatch '^\s*\*')
    }
}

function Resolve-TargetSerial {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [string]$Serial
    )

    $lines = @(Get-AdbDeviceLines -AdbPath $AdbPath | Where-Object { $_.Trim() -ne '' })
    $parsed = @(
        foreach ($line in $lines) {
            $parts = $line -split '\s+', 2
            [PSCustomObject]@{
                Serial = $parts[0]
                State  = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
            }
        }
    )

    if ($Serial) {
        $match = $parsed | Where-Object { $_.Serial -eq $Serial }
        if (-not $match) {
            $attached = if ($parsed.Count -gt 0) { ($parsed | ForEach-Object { "$($_.Serial) ($($_.State))" }) -join ', ' } else { '(none)' }
            throw "Device '$Serial' was not found in 'adb devices'. Attached: $attached"
        }
        if ($match.State -ne 'device') {
            throw "Device '$Serial' is attached but not ready (state: $($match.State)). If it says 'unauthorized', accept the USB-debugging prompt on the phone."
        }
        return $Serial
    }

    $ready = @($parsed | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -eq 0) {
        $seen = if ($parsed.Count -gt 0) { ($parsed | ForEach-Object { "$($_.Serial) ($($_.State))" }) -join ', ' } else { '(none)' }
        throw "No authorized device attached. Seen: $seen. Plug in the phone, enable USB debugging, and accept the prompt, or pass -Serial."
    }
    if ($ready.Count -gt 1) {
        $list = ($ready | ForEach-Object { $_.Serial }) -join ', '
        throw "Multiple devices attached ($list). Pass -Serial to pick one."
    }
    return $ready[0].Serial
}

function Invoke-AdbForward {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][int]$Port
    )

    $output = & $AdbPath -s $Serial forward "tcp:$Port" "tcp:$Port" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb forward failed: $($output -join [Environment]::NewLine)"
    }
}

function Remove-AdbForward {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][int]$Port
    )

    $output = & $AdbPath -s $Serial forward --remove "tcp:$Port" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-WarnMsg "Could not remove adb forward cleanly (it may already be gone): $($output -join [Environment]::NewLine)"
    }
}

# ----------------------------------------------------------------------------
# Probe
# ----------------------------------------------------------------------------

function Test-ProxyListening {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutMs = 2000
    )

    $url = "http://127.0.0.1:$Port/"
    try {
        $request = [System.Net.HttpWebRequest]::Create($url)
        $request.Proxy = $null
        $request.Method = 'GET'
        $request.Timeout = $TimeoutMs
        $request.ReadWriteTimeout = $TimeoutMs

        try {
            $response = $request.GetResponse()
            $response.Close()
            return $true
        }
        catch [System.Net.WebException] {
            # Any HTTP response (404, 400, 500, ...) means something is
            # listening and answering. A connection failure/timeout has no
            # Response at all.
            if ($_.Exception.Response) {
                $_.Exception.Response.Close()
                return $true
            }
            return $false
        }
    }
    catch {
        return $false
    }
}

# ----------------------------------------------------------------------------
# Proxy settings (HKCU Internet Settings)
# ----------------------------------------------------------------------------

function Get-ProxySetting {
    param([Parameter(Mandatory)][string]$Name)

    try {
        $value = Get-ItemPropertyValue -Path $script:InternetSettingsKey -Name $Name -ErrorAction Stop
        return [PSCustomObject]@{ Present = $true; Value = $value }
    }
    catch {
        return [PSCustomObject]@{ Present = $false; Value = $null }
    }
}

function Save-ProxySettings {
    $saved = @{}
    foreach ($name in $script:ProxySettingNames) {
        $saved[$name] = Get-ProxySetting -Name $name
    }
    return $saved
}

function Set-ProxySettings {
    param([Parameter(Mandatory)][int]$Port)

    Set-ItemProperty -Path $script:InternetSettingsKey -Name 'ProxyEnable' -Value 1 -Type DWord
    Set-ItemProperty -Path $script:InternetSettingsKey -Name 'ProxyServer' -Value "127.0.0.1:$Port" -Type String
    Set-ItemProperty -Path $script:InternetSettingsKey -Name 'ProxyOverride' -Value '<local>' -Type String
    Remove-ItemProperty -Path $script:InternetSettingsKey -Name 'AutoConfigURL' -ErrorAction SilentlyContinue
}

function Restore-ProxySettings {
    param([Parameter(Mandatory)][hashtable]$Saved)

    foreach ($name in $script:ProxySettingNames) {
        $entry = $Saved[$name]
        if ($entry.Present) {
            if ($name -eq 'ProxyEnable') {
                Set-ItemProperty -Path $script:InternetSettingsKey -Name $name -Value $entry.Value -Type DWord
            }
            else {
                Set-ItemProperty -Path $script:InternetSettingsKey -Name $name -Value $entry.Value -Type String
            }
        }
        else {
            Remove-ItemProperty -Path $script:InternetSettingsKey -Name $name -ErrorAction SilentlyContinue
        }
    }
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

$script:ForwardCreated = $false
$script:SettingsChanged = $false
$script:SavedSettings = $null
$script:ResolvedAdbPath = $null
$script:TargetSerial = $null

try {
    $script:ResolvedAdbPath = Resolve-AdbPath -ProvidedPath $AdbPath
    Write-Status "Using adb: $script:ResolvedAdbPath"

    $script:TargetSerial = Resolve-TargetSerial -AdbPath $script:ResolvedAdbPath -Serial $Serial
    Write-Status "Using device: $script:TargetSerial"

    if ($DryRun) {
        Write-Action "Would run: adb -s $script:TargetSerial forward tcp:$Port tcp:$Port"
        Write-Action "Would probe http://127.0.0.1:$Port/ with no proxy and a short timeout"
        Write-Action "Would save current values of $($script:ProxySettingNames -join ', ') under $script:InternetSettingsKey"
        Write-Action "Would set ProxyEnable=1, ProxyServer=127.0.0.1:$Port, ProxyOverride=<local>, and remove AutoConfigURL"
        Write-Action "Would notify WinINet (InternetSetOption INTERNET_OPTION_SETTINGS_CHANGED, INTERNET_OPTION_REFRESH)"
        Write-Action "Would print status and the 'netsh winhttp import proxy source=ie' hint, then wait for Enter"
        Write-Action "Would restore the saved proxy values exactly, notify WinINet again, and run: adb -s $script:TargetSerial forward --remove tcp:$Port"
        Write-Success 'Dry run complete. Nothing was changed.'
        exit 0
    }

    try {
        Write-Status "Forwarding tcp:$Port (laptop) -> tcp:$Port (device) over USB..."
        Invoke-AdbForward -AdbPath $script:ResolvedAdbPath -Serial $script:TargetSerial -Port $Port
        $script:ForwardCreated = $true
        Write-Success 'Forward created.'

        Write-Status "Probing http://127.0.0.1:$Port/ ..."
        if (-not (Test-ProxyListening -Port $Port)) {
            Write-WarnMsg "Nothing is listening on 127.0.0.1:$Port yet."
            Write-WarnMsg 'Start sharing in the app first, then re-run this script.'
            return
        }
        Write-Success 'The app is listening. Switching the Windows system proxy...'

        $script:SavedSettings = Save-ProxySettings
        foreach ($name in $script:ProxySettingNames) {
            $entry = $script:SavedSettings[$name]
            if ($entry.Present) {
                Write-Status "  Saved $name = $($entry.Value)"
            }
            else {
                Write-Status "  $name was not set; it will be removed again on restore"
            }
        }

        Set-ProxySettings -Port $Port
        $script:SettingsChanged = $true
        Send-WinInetSettingsChanged
        Write-Success "System proxy set to 127.0.0.1:$Port (bypass: <local>)"

        Write-Host ''
        Write-Status 'Hint: apps that talk WinHTTP directly (not WinINet/IE settings) may need an admin to run:'
        Write-Host '  netsh winhttp import proxy source=ie' -ForegroundColor White
        Write-Host ''
        Write-Status 'Sharing is active. Press Enter to stop and restore your previous proxy settings.'
        Read-Host | Out-Null
    }
    finally {
        if ($script:SettingsChanged) {
            Write-Status 'Restoring previous proxy settings...'
            Restore-ProxySettings -Saved $script:SavedSettings
            Send-WinInetSettingsChanged
            Write-Success 'Proxy settings restored.'
        }
        if ($script:ForwardCreated) {
            Write-Status "Removing adb forward tcp:$Port..."
            Remove-AdbForward -AdbPath $script:ResolvedAdbPath -Serial $script:TargetSerial -Port $Port
            Write-Success 'Forward removed.'
        }
    }
}
catch {
    Write-Err "Error: $($_.Exception.Message)"
    exit 1
}
