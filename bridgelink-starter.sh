#!/usr/bin/env bash
# Mirto-Launcher starter — runs the packaged executable jar.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Version-agnostic lookup of the shaded jar (newest build wins)
JAR="$(ls -1t "$SCRIPT_DIR"/target/mirto-launcher-*.jar 2>/dev/null | head -1 || true)"

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    echo "Error: no mirto-launcher jar found in $SCRIPT_DIR/target." >&2
    echo "Build it first with: mvn package (in $SCRIPT_DIR)" >&2
    exit 1
fi

# Run from the project root so the launcher's relative "jre/bin/java" lookup
# resolves deterministically, and make sure a JavaFX-capable JRE is present.
cd "$SCRIPT_DIR"
if [[ ! -x jre/bin/java ]]; then
    ./setup-jre.sh
fi

# Mirth must run on the bundled JavaFX JRE (its openjfx.jar has no natives);
# JAVA_HOME is the launcher's fallback when no bundled path matches.
export JAVA_HOME="$SCRIPT_DIR/jre"

exec java ${JAVA_OPTS:-} -jar "$JAR" "$@"
