# bridgelink-launcher
An open source Admin Launcher for BridgeLink (and OSS Mirth Connect)

## Download

Latest release: **[v1.7.0](https://github.com/marcheschi/BridgeLink-launcher/releases/tag/v1.7.0)**

| Platform | File | Notes |
|---|---|---|
| Windows x64 | [BridgeLinkLauncher-1.7.0-windows-x64-setup.exe](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/BridgeLinkLauncher-1.7.0-windows-x64-setup.exe) | Installer with **embedded Java 17 + JavaFX** — no Java installation required |
| Linux (Debian/Ubuntu) | [bridgelink-launcher_1.7.0_amd64.deb](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/bridgelink-launcher_1.7.0_amd64.deb) | .deb package with **embedded Java 17 + JavaFX** — no Java installation required |
| Linux (any distro) | [BridgeLink-Launcher-1.7.0-x86_64.AppImage](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/BridgeLink-Launcher-1.7.0-x86_64.AppImage) | Portable AppImage with **embedded Java 17 + JavaFX** — `chmod +x` and run |
| macOS (Intel) | [BridgeLink-Launcher-1.7.0-macos_x86_64.dmg](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/BridgeLink-Launcher-1.7.0-macos_x86_64.dmg) | Drag-to-install `.app` with **embedded Java 17 + JavaFX** — no Java installation required |
| macOS (Apple Silicon) | [BridgeLink-Launcher-1.7.0-macos_arm64.dmg](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/BridgeLink-Launcher-1.7.0-macos_arm64.dmg) | Drag-to-install `.app` with **embedded Java 17 + JavaFX** — no Java installation required |
| Cross-platform | [bridge-link-launcher-1.7.0.jar](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/bridge-link-launcher-1.7.0.jar) | Executable jar, run with any JDK 17+: `java -jar bridge-link-launcher-1.7.0.jar` |
| Linux | [bridgelink-starter.sh](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/bridgelink-starter.sh) | One-shot starter: runs the jar and auto-provisions the JavaFX JRE |
| Linux | [setup-jre.sh](https://github.com/marcheschi/BridgeLink-launcher/releases/download/v1.7.0/setup-jre.sh) | Provisions a Zulu FX 17 runtime into `./jre` (idempotent) |

All downloads: [Releases page](https://github.com/marcheschi/BridgeLink-launcher/releases)

## Windows Release (installer with embedded Java)

The Windows release ships as a single `.exe` installer (`BridgeLinkLauncher-<version>-windows-x64-setup.exe`)
that embeds a **private Java 17 runtime with JavaFX** (Zulu FX): end users do **not** need Java installed.

What it installs (under `%LocalAppData%\Programs\BridgeLinkLauncher`, no admin rights required):

- `BridgeLinkLauncher.exe` — native launcher (launch4j) bound to the embedded `jre\`
- `bridge-link-launcher-<version>.jar` — the application
- `jre\` — embedded JavaFX 17 runtime (this is the "Bundled Java 17" used at launch)
- `lib\java-console.jar` — helper for the "Show Java Console" option

Build it locally on a Windows machine with:

```powershell
powershell -ExecutionPolicy Bypass -File .\build\windows\build-installer.ps1
```

The script builds with Maven (`-Pwindows-release`), provisions the JRE, assembles the app folder
and compiles the installer with Inno Setup 6 (output in `build/windows/output/`).

CI does the same automatically on every GitHub release (see `.github/workflows/windows-release.yml`):
the installer is built on a Windows runner and attached to the release as an artifact.

The complete release procedure (version bump, tag, GitHub release, CI Windows
installer, asset upload and verification) is documented in [RELEASE.md](RELEASE.md).

Notes:

- The launcher prefers `javaw.exe` on Windows, so no extra console window appears.
- The installer is per-user; saved connections live in `data\` inside the install folder and
  are kept when upgrading (only `cache\` is removed on uninstall).
- Connections that use a **custom** Java home are unaffected; connections using "Bundled Java"
  will use the embedded runtime installed alongside the app.

## Linux Packages (.deb and AppImage)

Both Linux packages embed the **Zulu FX 17 runtime** (Java + JavaFX): no system Java is needed.

**.deb** (`bridgelink-launcher_<version>_amd64.deb`, Debian/Ubuntu and derivatives):

- Installs to `/opt/bridgelink-launcher` (jar, `lib/java-console.jar`, `jre/`)
- `bridgelink-launcher` command in `/usr/bin`, desktop entry and hicolor icons
- `sudo dpkg -i bridgelink-launcher_<version>_amd64.deb` or `sudo apt install ./bridgelink-launcher_<version>_amd64.deb`

**AppImage** (`BridgeLink-Launcher-<version>-x86_64.AppImage`, any x86_64 distro):

- Single portable file: `chmod +x` and run; ideal for distros without .deb support or no-root installs
- Connection data persists in `~/.local/share/bridgelink-launcher` (the mount is read-only)

Build them locally with `./build/linux/build-deb.sh` and `./build/linux/build-appimage.sh`
(see [RELEASE.md](RELEASE.md)); CI builds and attaches both to every GitHub release
(see `.github/workflows/linux-packages.yml`).

## MacOS Specific Instructions
Because the application is not signed by Apple, you may get a security warning and have to manually override your security settings to grant an exception to the launcher.

**Known Issue:**
If you extract the BridgeLink Launcher application straight to your Downloads folder, it will give you an error about "Read-Only Filesystem" when you try to save an entry.

To prevent this, you can do one of the following:
- Move the application to a different folder, such as /Applications or ~/Applications
- Create a new folder inside downloads then move the application there
- Enable All Applications in MacOS Gatekeeper by following these steps:
  * Open up System Settings
  * In System Settings, navigate to "Privacy & Security". Leave Window Open in the Background
  * Open up Terminal (as separate window). DO NOT CLOSE System Settings
  * In Terminal, run "sudo spctl --master-disable" --> Type Password --> Click Enter
  * In System Settings, navigate out of "Privacy & Security" Page (For Example -- Click on "Lockscreen"), then navigate back to "Privacy & Security"
  * In System Settings --> Privacy & Security Page --> Scroll Down to bottom --> Select "Allow Application From" --> Select "Anywhere" (the option will now appear) --> Type Password
Completed

## Upgrading via macOS DMG

When installing a new version of BridgeLink Launcher using the macOS `.dmg`, your connection data is stored inside the application bundle and **will be overwritten** if you simply drag the new version into `/Applications`.

To preserve your connections and settings, follow these steps before upgrading:

1. **Before upgrading**, open Finder and navigate to your current application:
   - Right-click `BridgeLink Administrator Launcher.app` → **Show Package Contents**
   - Navigate to `Contents/Resources/app/data/`
   - Copy the entire `data` folder to a safe temporary location (e.g., your Desktop)

2. **Install the new version** by mounting the new `.dmg` and dragging the application to `/Applications`, replacing the old one.

3. **After upgrading**, restore your data:
   - Right-click the newly installed app → **Show Package Contents**
   - Navigate to `Contents/Resources/app/`
   - Copy your saved `data` folder back into this location, replacing the new empty one

Your connections and settings will now be restored in the upgraded application.


## License

This project is licensed under the Mozilla Public License 2.0 (MPL-2.0). 

You are free to use, modify, and distribute this software under the terms of the MPL-2.0 license. This license requires that if you distribute modified versions of this software, you must also make the source code of those modifications available under the MPL-2.0.

For full details, see the [LICENSE](LICENSE) file or visit the [MPL-2.0 documentation](https://www.mozilla.org/en-US/MPL/2.0/).
