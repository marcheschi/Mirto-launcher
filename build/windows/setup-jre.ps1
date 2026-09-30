# Provisions a JavaFX-capable JRE (Zulu FX 17, Windows x64) into .\jre next to
# this script. BridgeLink's client ships a classes-only openjfx.jar, so Mirth
# must run on a JDK with built-in JavaFX; plain Corretto/OpenJDK fail with
# "Error initializing QuantumRenderer: no suitable pipeline found".
#
# Idempotent: re-running does nothing when a valid JRE is already present.
#
# Usage (from PowerShell):
#   powershell -ExecutionPolicy Bypass -File .\setup-jre.ps1

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Target    = Join-Path $ScriptDir "jre"
$ZuluUrl   = "https://cdn.azul.com/zulu/bin/zulu17.66.19-ca-fx-jdk17.0.19-win_x64.zip"

function Test-FxJre {
    param([string]$Path)
    if (-not (Test-Path (Join-Path $Path "bin\java.exe"))) { return $false }
    # JavaFX markers: Zulu FX / OpenJFX layout
    return (Test-Path (Join-Path $Path "lib\javafx.properties")) -or
           (Test-Path (Join-Path $Path "lib\javafx-swt.jar"))
}

if (Test-FxJre $Target) {
    Write-Host "OK: $Target already present."
    exit 0
}

# Remove a previous broken/incomplete provisioning attempt
if (Test-Path $Target) { Remove-Item -Recurse -Force $Target }

Write-Host "Downloading Zulu FX 17 ($ZuluUrl) ..."
$Tmp = Join-Path $env:TEMP ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $Tmp | Out-Null
try {
    $ZipPath = Join-Path $Tmp "zulu-fx.zip"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    # Retry a few times: the Azul CDN occasionally serves truncated downloads.
    $attempt = 0
    do {
        $attempt++
        try {
            Invoke-WebRequest -Uri $ZuluUrl -OutFile $ZipPath -UseBasicParsing
            break
        } catch {
            if ($attempt -ge 3) { throw }
            Write-Host "Download attempt ${attempt} failed: $($_.Exception.Message). Retrying..."
            Start-Sleep -Seconds 10
        }
    } while ($true)

    # Validate before expanding: a truncated or HTML error page would otherwise
    # expand to nothing and fail later with an opaque null-path Move-Item error.
    if (-not (Test-Path $ZipPath)) { throw "Download produced no file." }
    $Size = (Get-Item $ZipPath).Length
    if ($Size -lt 50MB) { throw "Download looks truncated (${Size} bytes, expected > 50 MB)." }
    $fs = [System.IO.File]::OpenRead($ZipPath)
    try {
        $Header = New-Object byte[] 2
        [void]$fs.Read($Header, 0, 2)
    } finally { $fs.Close() }
    if ($Header[0] -ne 0x50 -or $Header[1] -ne 0x4B) { throw "Download is not a zip archive (bad magic bytes)." }

    Expand-Archive -Path $ZipPath -DestinationPath $Tmp -Force

    # The Azul zip normally wraps everything in one top-level folder; handle the
    # flat layout too, and fail loudly with diagnostics otherwise.
    $Inner = Get-ChildItem -Path $Tmp -Directory | Select-Object -First 1
    if ($null -ne $Inner) {
        Move-Item -Path $Inner.FullName -Destination $Target
    } else {
        $Files = Get-ChildItem -Path $Tmp -File | Where-Object { $_.Name -ne "zulu-fx.zip" }
        if (-not $Files) { throw "Extraction produced no files or folders in ${Tmp}." }
        New-Item -ItemType Directory -Path $Target | Out-Null
        foreach ($f in $Files) { Move-Item -Path $f.FullName -Destination $Target }
    }
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}

if (-not (Test-FxJre $Target)) {
    Write-Error "$Target does not look like a JavaFX-capable JRE."
    exit 1
}

Write-Host "OK: provisioned $($Target)."
