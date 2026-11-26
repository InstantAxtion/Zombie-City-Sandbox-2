#!/usr/bin/env bash
# Plays the game in a desktop window (Java 8 or newer). Mouse to touch, wheel to zoom, Esc = back.
#   ./play.sh [width height]
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/play
javac -nowarn -encoding UTF-8 -d build/play $(find src/core desktop/src -name "*.java")
exec java -Xmx3g -cp build/play com.instantaxtion.zcs2.DesktopMain "$@"
