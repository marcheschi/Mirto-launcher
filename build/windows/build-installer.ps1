# Builds the Windows release of Mirto-Launcher:
#
#   1. mvn -Pwindows-release package   -> shaded jar + MirtoLauncher.exe (launch4j)
#   2. Provisions the embedded JavaFX JRE (Zulu FX 17) into build\windows\app\jre
#   3. Assembles build\windows\app\    -> exe, jar, lib\java-console.jar, jre\
#   4. Compiles build\windows\installer.iss with Inno Setup (ISCC.exe)
#
# The installer installs into %LocalAppData%\Programs\MirtoLauncher and
# embeds the JRE, so end users need no Java installed.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build-installer.ps1
#   powershell -ExecutionPolicy Bypass -File .\build-installer.ps1 -SkipBuild   # repackage only
#   powershell -ExecutionPolicy Bypass -File .\build-installer.ps1 -SkipInstaller

param(
    [switch]$SkipBuild,
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root       = Resolve-Path (Join-Path $ScriptDir "..\..")
$AppDir     = Join-Path $ScriptDir "app"
$OutDir     = Join-Path $ScriptDir "output"
$PomXml     = Join-Path $Root "pom.xml"

# Read <version> from pom.xml
[xml]$Pom = Get-Content $PomXml
$Version   = $Pom.project.version
if (-not $Version) {
    Write-Error "Cannot read <version> from $PomXml"
}
Write-Host "Mirto-Launcher version: $Version"

# ---------------------------------------------------------------- build step
if (-not $SkipBuild) {
    Push-Location $Root
    try {
        Write-Host "==> Building with Maven (profile windows-release) ..."
        # Prefer a JDK 17 for the build if available; falls back to the default one.
        mvn -B -Pwindows-release -DskipTests package
        if ($LASTEXITCODE -ne 0) { Write-Error "Maven build failed." }
    } finally {
        Pop-Location
    }
}

$JarPath = Join-Path $Root "target\mirto-launcher-$Version.jar"
$ExePath = Join-Path $Root "target\MirtoLauncher.exe"
if (-not (Test-Path $JarPath)) { Write-Error "Missing $JarPath (build first)." }
if (-not (Test-Path $ExePath)) {
    Write-Warning "Missing ${ExePath}: it is produced by the launch4j step of the windows-release Maven profile."
}

# ------------------------------------------------------------ assemble step
Write-Host "==> Assembling app folder in $AppDir ..."
if (Test-Path $AppDir) { Remove-Item -Recurse -Force $AppDir }
New-Item -ItemType Directory -Path $AppDir | Out-Null

Copy-Item (Join-Path $Root "target\mirto-launcher-$Version.jar") $AppDir
if (Test-Path $ExePath) { Copy-Item $ExePath $AppDir }

# Java console helper used by the "Show Java Console" option (ProcessLauncher
# runs it as  lib/java-console.jar  relative to the install dir).
New-Item -ItemType Directory -Path (Join-Path $AppDir "lib") | Out-Null
Copy-Item (Join-Path $Root "lib\java-console.jar") (Join-Path $AppDir "lib\java-console.jar")

# Embedded JavaFX JRE (provisioned in build\windows\jre, then copied into the
# app folder so the installer embeds it)
Write-Host "==> Provisioning embedded JavaFX JRE ..."
& (Join-Path $ScriptDir "setup-jre.ps1")
Write-Host "==> Copying JRE into app folder ..."
Copy-Item -Recurse -Force (Join-Path $ScriptDir "jre") (Join-Path $AppDir "jre")

# ------------------------------------------------------------- installer step
if (-not $SkipInstaller) {
    $IssPath = Join-Path $ScriptDir "installer.iss"
    if (-not (Test-Path $IssPath)) { Write-Error "Missing $IssPath" }

    # Locate Inno Setup compiler
    $Iscc = (Get-Command "ISCC.exe" -ErrorAction SilentlyContinue).Source
    if (-not $Iscc) {
        $Candidates = @(
            "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
            "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
        )
        foreach ($c in $Candidates) {
            if ($c -and (Test-Path $c)) { $Iscc = $c; break }
        }
    }
    if (-not $Iscc) {
        Write-Error "Inno Setup 6 (ISCC.exe) not found. Install it from https://jrsoftware.org/isdl.php"
    }

    Write-Host "==> Compiling installer with ISCC ($Iscc) ..."
    & $Iscc "/DAppVersion=$Version" $IssPath
    if ($LASTEXITCODE -ne 0) { Write-Error "Inno Setup compilation failed." }

    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
    Write-Host "OK: installer created in $OutDir"
}

Write-Host "Done. Version $Version"
