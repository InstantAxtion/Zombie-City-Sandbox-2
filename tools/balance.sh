#!/usr/bin/env bash
# Plays the same outbreak on many seeds (headless, fast-forward) and reports the spread of outcomes.
#   tools/balance.sh [sizes] [seeds] [map] [days] [threads]
#   tools/balance.sh 2000,20000,250000 8 2304 30 2
# Every run is also written to build/balance.csv.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/balance
javac -nowarn -encoding UTF-8 -d build/balance $(find src/core desktop/src -name "*.java") 2>&1 | grep -v "^Picked up" || true
java -Xmx3g -cp build/balance com.instantaxtion.zcs2.Balance "$@" 2>&1 | grep -v "^Picked up"
