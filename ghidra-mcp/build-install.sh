#!/usr/bin/env bash
# Build the vendored GhidraMCP extension jar and install it into the Ghidra Extensions dir.
# The one jar serves BOTH the GUI plugin (GhidraMCPPlugin) and the headless host (GhidraMCPServer).
set -euo pipefail

GHIDRA="${GHIDRA_INSTALL_DIR:-/home/trent/projects/ghidra/build/dist/ghidra_12.1_DEV}"
export GHIDRA_INSTALL_DIR="$GHIDRA"
[ -d "$GHIDRA" ] || { echo "GHIDRA_INSTALL_DIR not found: $GHIDRA"; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GHIDRA_VER="$(basename "$GHIDRA")"                 # e.g. ghidra_12.1_DEV
EXT_ROOT="$HOME/.config/ghidra/$GHIDRA_VER/Extensions/GhidraMCP"

echo "== building GhidraMCP.jar against $GHIDRA =="
mvn -f "$HERE/pom.xml" -q clean package

JAR="$HERE/target/GhidraMCP.jar"
[ -f "$JAR" ] || { echo "build produced no $JAR"; exit 1; }

echo "== installing to $EXT_ROOT =="
mkdir -p "$EXT_ROOT/lib"
cp "$JAR" "$EXT_ROOT/lib/GhidraMCP.jar"
cp "$HERE/src/main/resources/extension.properties" "$EXT_ROOT/extension.properties"
cp "$HERE/src/main/resources/Module.manifest"      "$EXT_ROOT/Module.manifest"

echo "installed $(du -h "$EXT_ROOT/lib/GhidraMCP.jar" | cut -f1) -> $EXT_ROOT/lib/GhidraMCP.jar"
echo "Restart Ghidra (GUI) to pick up the new plugin jar; the headless host loads it directly."
