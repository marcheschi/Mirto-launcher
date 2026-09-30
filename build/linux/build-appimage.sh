#!/usr/bin/env bash
# Builds a self-contained AppImage of Mirto-Launcher with an embedded
# JavaFX runtime (Zulu FX 17). The AppDir bundles: executable jar,
# lib/java-console.jar, jre/, AppRun starter, .desktop entry and icons.
#
# The AppImage mounts to a read-only location under /tmp/.mount_*, so the
# launcher's relative data/cache folders would be unusable: AppRun therefore
# passes the data directory as the first argument (the launcher supports an
# app-dir override as args[0], see BridgeLinkLauncher.initializeDirectories),
# pointing to ~/.local/share/mirto-launcher so data persists across runs.
#
# Requirements on the build host: bash, curl (to fetch appimagetool once),
# mksquashfs (squashfs-tools), file. Runs the downloaded appimagetool with
# --appimage-extract-and-run so FUSE is not required.
#
# Usage:
#   ./build-appimage.sh              # build jar if missing, provision jre, pack
#   SKIP_BUILD=1 ./build-appimage.sh # reuse target/ jar as-is
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="$SCRIPT_DIR/work/appimage"
OUT="$SCRIPT_DIR/output"
APPIMAGETOOL="$SCRIPT_DIR/work/appimagetool-x86_64.AppImage"

APP="mirto-launcher"
APPNAME="Mirto-Launcher"

# ---------------------------------------------------------------------------
# Version (from pom.xml)
# ---------------------------------------------------------------------------
VERSION="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$ROOT/pom.xml" | head -1)"
if [[ -z "$VERSION" ]]; then
    echo "ERROR: cannot read <version> from $ROOT/pom.xml" >&2
    exit 1
fi
echo "==> Mirto-Launcher version: $VERSION"

# ---------------------------------------------------------------------------
# 1. Jar
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" != "1" || ! -f "$ROOT/target/mirto-launcher-$VERSION.jar" ]]; then
    echo "==> Building jar (mvn -Pwindows-release package) ..."
    (cd "$ROOT" && mvn -B -Pwindows-release -DskipTests package)
fi
JAR="$ROOT/target/mirto-launcher-$VERSION.jar"
[[ -f "$JAR" ]] || { echo "ERROR: missing $JAR" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. Embedded JavaFX runtime
# ---------------------------------------------------------------------------
if [[ ! -x "$ROOT/jre/bin/java" ]]; then
    echo "==> Provisioning JavaFX JRE ..."
    (cd "$ROOT" && ./setup-jre.sh)
fi

# ---------------------------------------------------------------------------
# 3. AppDir
# ---------------------------------------------------------------------------
echo "==> Assembling AppDir in $WORK ..."
rm -rf "$WORK"
APPDIR="$WORK/$APP.AppDir"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/lib"

cp "$JAR" "$APPDIR/"
cp "$ROOT/lib/java-console.jar" "$APPDIR/usr/lib/java-console.jar"
cp -a "$ROOT/jre" "$APPDIR/jre"
cp "$ROOT/src/main/resources/images/logo.png" "$APPDIR/logo.png"
cp "$ROOT/LICENSE" "$APPDIR/"
chmod 755 "$APPDIR"

# AppRun: resolves java, prepares a writable data dir and execs the launcher.
# The data dir is passed as first arg (app dir override supported by the app).
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
JAR="$(ls -1t "$HERE"/mirto-launcher-*.jar 2>/dev/null | head -1)"
[[ -n "${JAR:-}" && -f "$JAR" ]] || { echo "Error: launcher jar not found in $HERE" >&2; exit 1; }

if [[ -x "$HERE/jre/bin/java" ]]; then
    JAVA="$HERE/jre/bin/java"
else
    JAVA="$(command -v java || true)"
    [[ -n "$JAVA" ]] || { echo "Error: no bundled or system java found" >&2; exit 1; }
fi

# Writable, persistent data dir (AppImage mount is read-only)
DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/mirto-launcher"
# Compatibility: merge settings from the pre-rename location if present.
LEGACY_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/bridgelink-launcher"
if [[ -d "$LEGACY_DIR" && ! -f "$DATA_DIR/connections.json" ]]; then
    mkdir -p "$DATA_DIR"
    cp -rn "$LEGACY_DIR"/. "$DATA_DIR"/ 2>/dev/null || true
fi
mkdir -p "$DATA_DIR"

export JAVA_HOME="$HERE/jre"
exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" "$DATA_DIR" "$@"
APPRUN
chmod 755 "$APPDIR/AppRun"

# Desktop entry
cat > "$APPDIR/mirto-launcher.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=$APPNAME
Comment=Admin launcher for BridgeLink (and OSS Mirth Connect)
Exec=mirto-launcher
Icon=$APP
Terminal=false
Categories=Development;Network;
StartupWMClass=MirtoLauncher
X-AppImage-Version=$VERSION
DESKTOP

# Icons: hicolor set + root icon (appimagetool picks <name>.png or .DirIcon)
for size in 16 24 32 48 64 128 192 256; do
    dir="$APPDIR/usr/share/icons/hicolor/${size}x${size}/apps"
    mkdir -p "$dir"
    convert "$ROOT/src/main/resources/images/logo.png" -resize "${size}x${size}" "$dir/$APP.png"
done
cp "$APPDIR/usr/share/icons/hicolor/256x256/apps/$APP.png" "$APPDIR/$APP.png"
ln -sf "$APP.png" "$APPDIR/.DirIcon"

# --------------------------------------------------------------------- pack
if [[ ! -f "$APPIMAGETOOL" ]]; then
    echo "==> Downloading appimagetool ..."
    mkdir -p "$(dirname "$APPIMAGETOOL")"
    curl -fL --retry 3 -o "$APPIMAGETOOL" \
        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "$APPIMAGETOOL"
fi

echo "==> Packing AppImage ..."
mkdir -p "$OUT"
OUTFILE="$OUT/Mirto-Launcher-$VERSION-x86_64.AppImage"
rm -f "$OUTFILE"
# --appimage-extract-and-run: works without FUSE (CI, containers)
"$APPIMAGETOOL" --appimage-extract-and-run "$APPDIR" "$OUTFILE"

echo "OK: $OUTFILE ($(du -h "$OUTFILE" | cut -f1))"
