#!/usr/bin/env bash
# Runs the game's tests on a desktop JVM. The simulation has no Android code in it, so nothing is stubbed.
#   ./test.sh            every test
#   ./test.sh save       only the tests whose names start with "save"
#   ./test.sh --ui       also drive the whole interface with scripted taps and save screenshots to build/uishots
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/test
mkdir -p build/test
javac -nowarn -encoding UTF-8 -d build/test $(find src/core desktop/src -name "*.java") 2>&1 | grep -v "^Picked up" || true
[ -f build/test/com/instantaxtion/zcs2/Tests.class ] || { echo "compile failed"; exit 1; }
run() { java -Djava.awt.headless=true -Xmx3g -cp build/test "$@" 2>&1 | grep --line-buffered -v '^Picked up'; }
if [ "${1:-}" = "--ui" ]; then
    run com.instantaxtion.zcs2.Tests
    run com.instantaxtion.zcs2.UiShot build/uishots
    exit 0
fi
run com.instantaxtion.zcs2.Tests "$@"
exit "${PIPESTATUS[0]}"
