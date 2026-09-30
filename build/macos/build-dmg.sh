#!/usr/bin/env bash
# Builds the macOS application bundle (.app) and disk image (.dmg) for
# Mirto-Launcher with an embedded JavaFX runtime (Zulu FX 17). The app
# installs by drag-and-drop into /Applications; when that location is not
# writable, the launcher stores its data in ~/.mirto-launcher at runtime.
#
# Usage:
#   ./build-dmg.sh x64                 # Intel DMG (builds the jar if missing)
#   SKIP_BUILD=1 ./build-dmg.sh aarch64  # Apple Silicon DMG, reuse target/ jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$SCRIPT_DIR/output"

# Sources tried in order: the GitHub release mirror first (fast and reliable from
# CI runners), then the Azul CDN. The Azul CDN has been flaky for runner traffic
# (truncated downloads, HTTP/2 stream errors).
GH_BASE="https://github.com/marcheschi/Mirto-launcher/releases/download/v1.8.1"

ARCH="${1:-x64}"
case "$ARCH" in
  x64)     GH_URL="$GH_BASE/build-dep-zulu-fx-17-macosx_x64.tar.gz"; ZULU_URL="https://cdn.azul.com/zulu/bin/zulu17.68.203-ca-fx-jdk17.0.20.1-macosx_x64.tar.gz"; SUFFIX="x86_64" ;;
  aarch64) GH_URL="$GH_BASE/build-dep-zulu-fx-17-macosx_aarch64.tar.gz"; ZULU_URL="https://cdn.azul.com/zulu/bin/zulu17.68.203-ca-fx-jdk17.0.20.1-macosx_aarch64.tar.gz"; SUFFIX="arm64" ;;
  *) echo "ERROR: unknown architecture '$ARCH' (expected x64 or aarch64)" >&2; exit 1 ;;
esac

WORK="$SCRIPT_DIR/work/dmg-$SUFFIX"

# ---------------------------------------------------------------------------
# Version (from pom.xml)
# ---------------------------------------------------------------------------
VERSION="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$ROOT/pom.xml" | head -1)"
if [[ -z "$VERSION" ]]; then
    echo "ERROR: cannot read <version> from $ROOT/pom.xml" >&2
    exit 1
fi
echo "==> Mirto-Launcher version: $VERSION (macOS $SUFFIX)"

# ---------------------------------------------------------------------------
# 1. Jar
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" != "1" || ! -f "$ROOT/target/mirto-launcher-$VERSION.jar" ]]; then
    echo "==> Building jar (mvn package) ..."
    (cd "$ROOT" && mvn -B -DskipTests package)
fi
JAR="$ROOT/target/mirto-launcher-$VERSION.jar"
[[ -f "$JAR" ]] || { echo "ERROR: missing $JAR" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. App bundle
# ---------------------------------------------------------------------------
echo "==> Assembling app bundle in $WORK ..."
rm -rf "$WORK"
APP="$WORK/Mirto-Launcher.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/app/lib"

cp "$JAR" "$APP/Contents/Resources/app/"
cp "$ROOT/lib/java-console.jar" "$APP/Contents/Resources/app/lib/java-console.jar"

# Embedded JavaFX runtime (Zulu FX 17, matching architecture)
echo "==> Downloading Zulu FX 17 ($SUFFIX) ..."
# Retry with a full archive validation so a bad download fails fast instead of
# breaking later (the Azul CDN is known to serve truncated files / HTTP/2 errors).
ok=0
for url in "$GH_URL" "$ZULU_URL"; do
    for attempt in 1 2 3; do
        rm -f "$WORK/zulu-fx.tar.gz"
        if curl -fL --retry 3 --max-time 3600 -o "$WORK/zulu-fx.tar.gz" "$url" \
           && tar -tzf "$WORK/zulu-fx.tar.gz" >/dev/null 2>&1; then
            ok=1
            break 2
        fi
        echo "WARN: Zulu FX attempt $attempt from ${url%%/*} failed or produced a bad archive, retrying..." >&2
        sleep 15
    done
done
[[ $ok -eq 1 ]] || { echo "ERROR: could not download a valid Zulu FX archive" >&2; exit 1; }
tar -xzf "$WORK/zulu-fx.tar.gz" -C "$WORK"
rm -f "$WORK/zulu-fx.tar.gz"
JRE_DIR="$(find "$WORK" -maxdepth 1 -type d -name 'zulu*' | head -1)"
# macOS tarballs use the bundle layout (<dir>/Contents/Home); normalize to a
# flat runtime so the app's jre/bin/java path works everywhere.
if [[ -n "${JRE_DIR:-}" && -x "$JRE_DIR/Contents/Home/bin/java" ]]; then
    JRE_HOME="$JRE_DIR/Contents/Home"
else
    JRE_HOME="${JRE_DIR:-}"
fi
[[ -n "$JRE_HOME" && -x "$JRE_HOME/bin/java" ]] || { echo "ERROR: Zulu FX runtime not found after extraction" >&2; exit 1; }
mv "$JRE_HOME" "$APP/Contents/Resources/app/jre"

# App icon (best effort; requires sips/iconutil on macOS)
ICON_BLOCK=""
if [[ -f "$ROOT/src/main/resources/images/logo.png" ]] && command -v iconutil >/dev/null 2>&1; then
    ICONSET="$WORK/AppIcon.iconset"
    mkdir -p "$ICONSET"
    for size in 16 32 128 256 512; do
        sips -z "$size" "$size" "$ROOT/src/main/resources/images/logo.png" \
            --out "$ICONSET/icon_${size}x${size}.png" >/dev/null 2>&1 || true
        dbl=$((size * 2))
        sips -z "$dbl" "$dbl" "$ROOT/src/main/resources/images/logo.png" \
            --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null 2>&1 || true
    done
    if iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns" 2>/dev/null; then
        ICON_BLOCK="    <key>CFBundleIconFile</key>
    <string>AppIcon</string>"
    fi
fi

# Info.plist
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>Mirto-Launcher</string>
    <key>CFBundleDisplayName</key>
    <string>Mirto-Launcher</string>
    <key>CFBundleIdentifier</key>
    <string>com.mirto.launcher</string>
    <key>CFBundleExecutable</key>
    <string>MirtoLauncher</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>$VERSION</string>
    <key>CFBundleVersion</key>
    <string>$VERSION</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.15</string>
    <key>NSHighResolutionCapable</key>
    <true/>
$ICON_BLOCK
</dict>
</plist>
PLIST

# Launcher executable: run the jar with the bundled JavaFX runtime from the app
# directory (the launcher itself falls back to ~/.mirto-launcher for data
# when the install location is read-only).
cat > "$APP/Contents/MacOS/MirtoLauncher" <<'LAUNCHER'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$DIR/../Resources/app"

JAR="$(ls -1t "$APP"/mirto-launcher-*.jar 2>/dev/null | head -1)"
if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    echo "Error: no mirto-launcher jar found in $APP." >&2
    exit 1
fi

# Java: bundled (JavaFX-capable) runtime first, system fallback
if [[ -x "$APP/jre/bin/java" ]]; then
    JAVA="$APP/jre/bin/java"
else
    JAVA="$(command -v java || true)"
    if [[ -z "$JAVA" ]]; then
        echo "Error: bundled JRE missing and no system java found." >&2
        exit 1
    fi
fi

cd "$APP"
exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" "$@"
LAUNCHER
chmod 755 "$APP/Contents/MacOS/MirtoLauncher"

# ---------------------------------------------------------------------------
# 3. DMG (app + Applications shortcut, classic drag-to-install layout)
# ---------------------------------------------------------------------------
echo "==> Creating DMG ..."
mkdir -p "$OUT"
DMG_STAGING="$WORK/dmg"
mkdir -p "$DMG_STAGING"
cp -R "$APP" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"

DMG="$OUT/Mirto-Launcher-$VERSION-macos_$SUFFIX.dmg"
rm -f "$DMG"
hdiutil create -volname "Mirto-Launcher $VERSION" \
    -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG" >/dev/null

echo "OK: $DMG ($(du -h "$DMG" | cut -f1))"
