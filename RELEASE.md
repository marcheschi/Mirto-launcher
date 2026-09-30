# Release Procedure

How to cut a new Mirto-Launcher release. Following these steps produces:

- `mirto-launcher-<version>.jar` — executable jar (attached manually)
- `MirtoLauncher-<version>-windows-x64-setup.exe` — Windows installer with an
  embedded Java 17 + JavaFX runtime (built and attached **automatically by CI**)
- `mirto-launcher_<version>_amd64.deb` — Debian package with embedded
  Java 17 + JavaFX (built and attached **automatically by CI**)
- `Mirto-Launcher-<version>-x86_64.AppImage` — portable AppImage with
  embedded Java 17 + JavaFX (built and attached **automatically by CI**)

## 1. Bump the version

Two places must stay in sync:

| File | What to change |
|---|---|
| `pom.xml` | `<version>` in the project coordinates |
| `src/main/java/com/mirto/launcher/BridgeLinkLauncher.java` | `FALLBACK_VERSION` constant (used when the jar manifest has no version) |

```bash
# example: 1.5.0 -> 1.6.0
sed -i 's|<version>1.5.0</version>|<version>1.6.0</version>|' pom.xml
sed -i 's|FALLBACK_VERSION = "1.5.0"|FALLBACK_VERSION = "1.6.0"|' \
    src/main/java/com/mirto/launcher/BridgeLinkLauncher.java
```

## 2. Build and verify locally

```bash
# Shaded jar + Windows exe wrapper (launch4j runs fine on Linux too)
mvn -B -Pwindows-release -DskipTests package

# Expect: target/mirto-launcher-<version>.jar
#         target/MirtoLauncher.exe
ls -la target/
```

The `windows-release` profile is required: it produces `MirtoLauncher.exe`
via launch4j, configured to use the embedded `jre/` runtime shipped by the
installer.

## 3. Commit and push

```bash
git add pom.xml src/main/java/com/mirto/launcher/BridgeLinkLauncher.java
git commit -m "Bump version to <version>"
git push origin main
```

**Important:** the release-triggered Windows workflow lives on `main`
(`.github/workflows/windows-release.yml`). It must be pushed **before** creating
the release, otherwise the installer is not built.

## 4. Create the tag and the GitHub release

```bash
gh release create v<version> \
    --target main \
    --title "Mirto-Launcher <version>" \
    --notes "..."
```

Creating the release automatically triggers **two** workflows:

- **Windows Release** (`.github/workflows/windows-release.yml`): installs Inno
  Setup, builds with Maven, provisions the Zulu FX 17 runtime, compiles the
  installer and uploads it as a release asset.
- **Linux Packages** (`.github/workflows/linux-packages.yml`): builds the `.deb`
  (`build/linux/build-deb.sh`) and the AppImage (`build/linux/build-appimage.sh`),
  both with the embedded runtime, and uploads them as release assets.

## 5. Attach the Linux/cross-platform assets

```bash
gh release upload v<version> \
    target/mirto-launcher-<version>.jar \
    bridgelink-starter.sh \
    setup-jre.sh \
    --clobber
```

## 6. Verify

```bash
# Watch the workflow runs
gh run list --workflow=windows-release.yml --limit 1
gh run list --workflow=linux-packages.yml --limit 1
gh run watch <run-id>   # ~10 min each: the embedded JRE is big and slow to compress

# All six assets must be listed
gh release view v<version> --json assets --jq '.assets[].name'

# Expected:
#   MirtoLauncher-<version>-windows-x64-setup.exe   (built by CI)
#   mirto-launcher_<version>_amd64.deb              (built by CI)
#   Mirto-Launcher-<version>-x86_64.AppImage        (built by CI)
#   mirto-launcher-<version>.jar                   (uploaded in step 5)
#   bridgelink-starter.sh                                (uploaded in step 5)
#   setup-jre.sh                                         (uploaded in step 5)

# Direct download links must answer 200
curl -sIL -o /dev/null -w "%{http_code}\n" \
    "https://github.com/marcheschi/Mirto-launcher/releases/download/v<version>/MirtoLauncher-<version>-windows-x64-setup.exe"
```

Finally, update the **Download** section of `README.md` with the new version's
asset links and push.

## 7. (Optional) Build the Windows installer locally

On a Windows machine with Maven + JDK 17 + Inno Setup 6:

```powershell
powershell -ExecutionPolicy Bypass -File .\build\windows\build-installer.ps1
# output: build\windows\output\MirtoLauncher-<version>-windows-x64-setup.exe
```

## Troubleshooting

- **Installer asset missing after the release**: check
  `gh run list --workflow=windows-release.yml`. The most common cause is that
  the workflow file was not on `main` when the release was created — re-run the
  workflow manually from the Actions tab (it supports `workflow_dispatch`), then
  re-run the "Attach installer to release" step or upload with
  `gh release upload v<version> <installer.exe> --clobber`.
- **`401 Bad credentials` on `gh`**: the environment may carry an invalid
  `GITHUB_TOKEN`. Remove it for the command (`env -u GITHUB_TOKEN gh ...`) or
  use the `~/.local/bin/gh` wrapper, which unsets it automatically.
- **launch4j build failure**: the plugin version is pinned in the
  `windows-release` profile of `pom.xml`; `requiresJdk`/`requires64Bit` options
  require launch4j-maven-plugin 2.x.
