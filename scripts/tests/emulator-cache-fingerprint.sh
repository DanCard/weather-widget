#!/usr/bin/env bash
#
# scripts/lib/emulator-cache.sh: a pass is cached only for the source it ran against. An edit during
# the run used to be fingerprinted at write time, recording a pass for code that never ran.
#
# Usage: scripts/tests/emulator-cache-fingerprint.sh

set -u
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PROJECT_DIR="$WORK"
# shellcheck source=../lib/emulator-cache.sh
source "$ROOT_DIR/scripts/lib/emulator-cache.sh"

git -C "$WORK" init -q
mkdir -p "$WORK/app"
echo "one" > "$WORK/app/Source.kt"
git -C "$WORK" add app
git -C "$WORK" -c user.name=t -c user.email=t@t commit -qm init

failures=0
write_pass() { write_emulator_cache ALL Pixel 3 3 0 0 0 1 log "$WORK" "$1" 2> /dev/null; }

start=$(compute_emulator_fingerprint "$WORK")
write_pass "$start"
if is_emulator_cache_valid "" Pixel true "$WORK"; then
  echo "ok   unchanged source is cached"
else
  echo "FAIL unchanged source was not cached"; failures=$((failures + 1))
fi

clear_emulator_cache
start=$(compute_emulator_fingerprint "$WORK")
echo "two" > "$WORK/app/Source.kt"
write_pass "$start"
if is_emulator_cache_valid "" Pixel true "$WORK"; then
  echo "FAIL edit during the run was cached as passed"; failures=$((failures + 1))
else
  echo "ok   edit during the run is not cached"
fi

clear_emulator_cache
write_pass ""
if is_emulator_cache_valid "" Pixel true "$WORK"; then
  echo "FAIL pass with no start fingerprint was cached"; failures=$((failures + 1))
else
  echo "ok   missing start fingerprint is not cached"
fi

[ "$failures" -eq 0 ] && echo "PASS" || { echo "$failures case(s) failed"; exit 1; }
