#!/usr/bin/env bash
# Builds the macOS application bundle (.app) and disk image (.dmg) for
# BridgeLink Launcher with an embedded JavaFX runtime (Zulu FX 17). The app
# installs by drag-and-drop into /Applications; when that location is not
# writable, the launcher stores its data in ~/.bridgelink-launcher at runtime.
#
# Usage:
#   ./build-dmg.sh x64                 # Intel DMG (builds the jar if missing)
#   SKIP_BUILD=1 ./build-dmg.sh aarch64  # Apple Silicon DMG, reuse target/ jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$SCRIPT_DIR/output"

ARCH="${1:-x64}"
case "$ARCH" in
  x64)     ZULU_URL="https://cdn.azul.com/zulu/bin/zulu17.68.203-ca-fx-jdk17.0.20.1-macosx_x64.tar.gz"; SUFFIX="x86_64" ;;
  aarch64) ZULU_URL="https://cdn.azul.com/zulu/bin/zulu17.68.203-ca-fx-jdk17.0.20.1-macosx_aarch64.tar.gz"; SUFFIX="arm64" ;;
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
echo "==> BridgeLink Launcher version: $VERSION (macOS $SUFFIX)"

# ---------------------------------------------------------------------------
# 1. Jar
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" != "1" || ! -f "$ROOT/target/bridge-link-launcher-$VERSION.jar" ]]; then
    echo "==> Building jar (mvn package) ..."
    (cd "$ROOT" && mvn -B -DskipTests package)
fi
JAR="$ROOT/target/bridge-link-launcher-$VERSION.jar"
[[ -f "$JAR" ]] || { echo "ERROR: missing $JAR" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. App bundle
# ---------------------------------------------------------------------------
echo "==> Assembling app bundle in $WORK ..."
rm -rf "$WORK"
APP="$WORK/BridgeLink Launcher.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/app/lib"

cp "$JAR" "$APP/Contents/Resources/app/"
cp "$ROOT/lib/java-console.jar" "$APP/Contents/Resources/app/lib/java-console.jar"

# Embedded JavaFX runtime (Zulu FX 17, matching architecture)
echo "==> Downloading Zulu FX 17 ($SUFFIX) ..."
curl -fL --retry 3 -o "$WORK/zulu-fx.tar.gz" "$ZULU_URL"
tar -xzf "$WORK/zulu-fx.tar.gz" -C "$WORK"
rm -f "$WORK/zulu-fx.tar.gz"
JRE_DIR="$(find "$WORK" -maxdepth 1 -type d -name 'zulu*' | head -1)"
[[ -n "${JRE_DIR:-}" && -x "$JRE_DIR/bin/java" ]] || { echo "ERROR: Zulu FX runtime not found after extraction" >&2; exit 1; }
mv "$JRE_DIR" "$APP/Contents/Resources/app/jre"

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
    <string>BridgeLink Launcher</string>
    <key>CFBundleDisplayName</key>
    <string>BridgeLink Launcher</string>
    <key>CFBundleIdentifier</key>
    <string>com.innovarhealthcare.bridgelink-launcher</string>
    <key>CFBundleExecutable</key>
    <string>BridgeLinkLauncher</string>
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
# directory (the launcher itself falls back to ~/.bridgelink-launcher for data
# when the install location is read-only).
cat > "$APP/Contents/MacOS/BridgeLinkLauncher" <<'LAUNCHER'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$DIR/../Resources/app"

JAR="$(ls -1t "$APP"/bridge-link-launcher-*.jar 2>/dev/null | head -1)"
if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    echo "Error: no bridge-link-launcher jar found in $APP." >&2
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
chmod 755 "$APP/Contents/MacOS/BridgeLinkLauncher"

# ---------------------------------------------------------------------------
# 3. DMG (app + Applications shortcut, classic drag-to-install layout)
# ---------------------------------------------------------------------------
echo "==> Creating DMG ..."
mkdir -p "$OUT"
DMG_STAGING="$WORK/dmg"
mkdir -p "$DMG_STAGING"
cp -R "$APP" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"

DMG="$OUT/BridgeLink-Launcher-$VERSION-macos_$SUFFIX.dmg"
rm -f "$DMG"
hdiutil create -volname "BridgeLink Launcher $VERSION" \
    -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG" >/dev/null

echo "OK: $DMG ($(du -h "$DMG" | cut -f1))"
