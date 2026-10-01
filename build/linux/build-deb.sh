#!/usr/bin/env bash
# Builds the Debian package (.deb) for Mirto-Launcher with an embedded
# JavaFX runtime (Zulu FX 17). The package installs to /opt/mirto-launcher
# with a launcher script, .desktop entry and hicolor icons; the Java runtime is
# bundled, so no system JDK/JRE is required.
#
# Runtime data (connections) defaults to the install dir; when it is not
# writable (system-wide install), the launcher falls back to
# ~/.local/share/mirto-launcher (handled at runtime, see the starter
# script below).
#
# Usage:
#   ./build-deb.sh              # build jar if missing, provision jre, pack .deb
#   SKIP_BUILD=1 ./build-deb.sh # reuse target/ jar as-is
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="$SCRIPT_DIR/work/deb"
OUT="$SCRIPT_DIR/output"

# ---------------------------------------------------------------------------
# Version (from pom.xml)
# ---------------------------------------------------------------------------
VERSION="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$ROOT/pom.xml" | head -1)"
if [[ -z "$VERSION" ]]; then
    echo "ERROR: cannot read <version> from $ROOT/pom.xml" >&2
    exit 1
fi
echo "==> BridgeLink Launcher version: $VERSION"

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
# 2. Embedded JavaFX runtime (reuses the project's provisioning script)
# ---------------------------------------------------------------------------
if [[ ! -x "$ROOT/jre/bin/java" ]]; then
    echo "==> Provisioning JavaFX JRE ..."
    (cd "$ROOT" && ./setup-jre.sh)
fi

# ---------------------------------------------------------------------------
# 3. Package tree
# ---------------------------------------------------------------------------
echo "==> Assembling package tree in $WORK ..."
rm -rf "$WORK"
INSTALL_DIR="$WORK/opt/mirto-launcher"
BIN_DIR="$WORK/usr/bin"
DESKTOP_DIR="$WORK/usr/share/applications"
ICON_DIR="$WORK/usr/share/icons/hicolor"
DOC_DIR="$WORK/usr/share/doc/mirto-launcher"
mkdir -p "$INSTALL_DIR/lib" "$BIN_DIR" "$DESKTOP_DIR" "$DOC_DIR"

cp "$JAR" "$INSTALL_DIR/"
cp "$ROOT/lib/java-console.jar" "$INSTALL_DIR/lib/java-console.jar"
cp -a "$ROOT/jre" "$INSTALL_DIR/jre"

# Launcher script: prefer the system java when the bundled one cannot run
# (e.g. foreign-architecture package); data falls back to XDG data home when
# the install dir is read-only.
cat > "$BIN_DIR/mirto-launcher" <<'LAUNCHER'
#!/usr/bin/env bash
# Mirto-Launcher - starter installed by the .deb package
set -euo pipefail

APP_DIR="/opt/mirto-launcher"
JAR="$(ls -1t "$APP_DIR"/mirto-launcher-*.jar 2>/dev/null | head -1)"

if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    echo "Error: no mirto-launcher jar found in $APP_DIR." >&2
    exit 1
fi

# Java: bundled (JavaFX-capable) runtime first, system fallback
if [[ -x "$APP_DIR/jre/bin/java" ]]; then
    JAVA="$APP_DIR/jre/bin/java"
else
    JAVA="$(command -v java || true)"
    if [[ -z "$JAVA" ]]; then
        echo "Error: bundled JRE missing and no system java found." >&2
        exit 1
    fi
fi

# Data directory: install dir when writable, else XDG data home
DATA_PARENT="$APP_DIR"
if ! { [[ -w "$APP_DIR" ]] || [[ $EUID -eq 0 ]]; }; then
    DATA_PARENT="${XDG_DATA_HOME:-$HOME/.local/share}/mirto-launcher"
    # Compatibility: merge settings from the pre-rename location if present.
    LEGACY_PARENT="${XDG_DATA_HOME:-$HOME/.local/share}/bridgelink-launcher"
    if [[ -d "$LEGACY_PARENT" && ! -f "$DATA_PARENT/connections.json" ]]; then
        mkdir -p "$DATA_PARENT"
        cp -rn "$LEGACY_PARENT"/. "$DATA_PARENT"/ 2>/dev/null || true
    fi
    mkdir -p "$DATA_PARENT"
fi

cd "$DATA_PARENT"
exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" "$@"
LAUNCHER
chmod 755 "$BIN_DIR/mirto-launcher"

# Desktop entry
ICON_PATH="$INSTALL_DIR/logo.png"
cp "$ROOT/src/main/resources/images/logo.png" "$ICON_PATH"
sed "s|__ICON__|$ICON_PATH|g" > "$DESKTOP_DIR/mirto-launcher.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Mirto-Launcher
Comment=Admin launcher for BridgeLink (and OSS Mirth Connect)
Exec=mirto-launcher
Icon=__ICON__
Terminal=false
Categories=Development;Network;
# Must match the WM_CLASS JavaFX actually reports for this app (the main class),
# otherwise the dock/alt-tab cannot associate the running window with this entry
# and falls back to a generic icon + raw class name.
StartupWMClass=com.mirto.launcher.BridgeLinkLauncher
DESKTOP
chmod 644 "$DESKTOP_DIR/mirto-launcher.desktop"

# hicolor icons (source logo is square 192x192)
for size in 16 24 32 48 64 128 192 256; do
    dir="$ICON_DIR/${size}x${size}/apps"
    mkdir -p "$dir"
    convert "$ROOT/src/main/resources/images/logo.png" -resize "${size}x${size}" "$dir/mirto-launcher.png"
done

# Docs
cp "$ROOT/LICENSE" "$DOC_DIR/LICENSE"
cp "$ROOT/README.md" "$DOC_DIR/README.md"

# Control file
mkdir -p "$WORK/DEBIAN"
# Installed-Size (KiB) from real file bytes: du is unreliable on compressed FS
INSTALLED_KB=$(find "$WORK" -path "$WORK/DEBIAN" -prune -o -type f -printf '%s\n' \
    | awk '{s+=$1} END {print int((s+1023)/1024)}')
cat > "$WORK/DEBIAN/control" <<CONTROL
Package: mirto-launcher
Version: $VERSION
Section: devel
Priority: optional
Architecture: amd64
Installed-Size: $INSTALLED_KB
Depends: libc6 (>= 2.17)
Maintainer: Paolo Marcheschi <paolo.marcheschi@ftgm.it>
Homepage: https://github.com/marcheschi/Mirto-launcher
Description: Admin launcher for BridgeLink (and OSS Mirth Connect)
 JavaFX desktop application to download, configure and launch the
 BridgeLink/Mirth Connect administrator client from a JNLP endpoint.
 Features connection management (groups, icons, notes), SSH tunnel
 jumping, bundled/custom Java selection and heap configuration.
 Ships an embedded Zulu FX 17 runtime: no system Java is required.
CONTROL

# --------------------------------------------------------------------- pack
echo "==> Packing .deb ..."
mkdir -p "$OUT"
DEB="$OUT/mirto-launcher_${VERSION}_amd64.deb"
rm -f "$DEB"
dpkg-deb --root-owner-group --build "$WORK" "$DEB"

echo "OK: $DEB ($(du -h "$DEB" | cut -f1))"
