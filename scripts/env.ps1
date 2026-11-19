# Dot-source in every PowerShell session: . .\scripts\env.ps1
#
# DEVIATION from plan: winget install --id EclipseAdoptium.Temurin.21.JDK failed
# silently in this environment (no console output, no package installed, exit
# code inconsistent). JDK 21 was instead downloaded directly from Adoptium and
# extracted (no admin) under $env:LOCALAPPDATA\Programs\Temurin. This script
# searches both locations so it works whether the JDK came from winget
# (Program Files) or the zip fallback (LOCALAPPDATA).
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium', "$env:LOCALAPPDATA\Programs\Temurin" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw 'JDK 21 not found. Install with: winget install --id EclipseAdoptium.Temurin.21.JDK -e' }
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$($jdk.FullName)\bin;$env:Path"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
