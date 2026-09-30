#!/usr/bin/env bash
# Provisions a JavaFX-capable JRE (Zulu FX 17) into ./jre next to this script.
# BridgeLink's client ships a classes-only openjfx.jar, so Mirth must run on a
# JDK with built-in JavaFX; plain Corretto/OpenJDK fail with
# "Error initializing QuantumRenderer: no suitable pipeline found".
#
# Sources, in order of preference:
#   1. ./jre already present and valid            -> nothing to do (idempotent)
#   2. /opt/BridgeLink-Launcher/jre (official launcher install, same build)
#   3. Download Zulu FX 17 from the Azul CDN
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/jre"
LOCAL_SRC="/opt/BridgeLink-Launcher/jre"
ZULU_URL="https://cdn.azul.com/zulu/bin/zulu17.66.19-ca-fx-jdk17.0.19-linux_x64.tar.gz"

is_fx_jre() {
    [[ -x "$1/bin/java" ]] || return 1
    # JavaFX markers: Zulu FX / OpenJFX layout, or legacy JRE8 jfxrt.jar
    [[ -f "$1/lib/javafx.properties" || -f "$1/lib/libprism_sw.so" || -f "$1/lib/ext/jfxrt.jar" ]]
}

if is_fx_jre "$TARGET"; then
    echo "OK: $TARGET already present ($( "$TARGET/bin/java" -version 2>&1 | head -1 ))"
    exit 0
fi

# Remove a previous broken/incomplete provisioning attempt
rm -rf "$TARGET"

if is_fx_jre "$LOCAL_SRC"; then
    echo "Using local JavaFX JRE from $LOCAL_SRC ..."
    cp -a "$LOCAL_SRC" "$TARGET"
else
    echo "Downloading Zulu FX 17 ($ZULU_URL) ..."
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    # The Azul CDN is flaky (HTTP/2 stream errors, truncated files): retry with a
    # full archive validation so a bad download fails fast instead of breaking later.
    ok=0
    for attempt in 1 2 3; do
        rm -f "$tmp/zulu-fx.tar.gz"
        if curl -fL --retry 3 --max-time 3600 -o "$tmp/zulu-fx.tar.gz" "$ZULU_URL" \
           && tar -tzf "$tmp/zulu-fx.tar.gz" >/dev/null 2>&1; then
            ok=1
            break
        fi
        echo "WARN: Zulu FX attempt $attempt failed or produced a bad archive, retrying..." >&2
        sleep 15
    done
    [[ $ok -eq 1 ]] || { echo "ERROR: could not download a valid Zulu FX archive" >&2; exit 1; }
    tar -xzf "$tmp/zulu-fx.tar.gz" -C "$tmp"
    inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
    mv "$inner" "$TARGET"
fi

if ! is_fx_jre "$TARGET"; then
    echo "ERROR: $TARGET does not look like a JavaFX-capable JRE." >&2
    exit 1
fi

echo "OK: provisioned $( "$TARGET/bin/java" -version 2>&1 | head -1 )"
