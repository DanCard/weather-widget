#!/usr/bin/env bash
# Lists com.weatherwidget methods in the debug APK over the dex size budget (instructions or
# registers), largest first. Report-only for now: always exits 0 unless the APK or dexdump is missing.
#
# Why: ART rejected CloudCoverViewHandler.updateWidget's dex on 2026-11-19 (VerifyError). The defect
# came from an incremental build's bytecode (a clean build of the same commit verifies), so size is a
# risk heuristic, not a known threshold. The real guard is emulator-tests.sh's verify_app_dex.
# plans/261119-split-oversized-android-suspend-functions.md
#
# Usage: scripts/dex-method-budget.sh [apk]   (env: MAX_INSNS=6000 MAX_REGS=150 TOP=0)
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
MAX_INSNS="${MAX_INSNS:-6000}"
MAX_REGS="${MAX_REGS:-150}"
TOP="${TOP:-0}"   # >0: also print the TOP largest methods even when under budget

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.Android/Sdk}}"
[ -d "$SDK" ] || SDK="$HOME/Android/Sdk"
DEXDUMP=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)dexdump

if [ ! -f "$APK" ]; then echo "dex budget: no APK at $APK"; exit 1; fi
if [ ! -x "$DEXDUMP" ]; then echo "dex budget: dexdump not found under $SDK/build-tools"; exit 1; fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$WORK"

for dex in "$WORK"/classes*.dex; do
    "$DEXDUMP" "$dex" 2>/dev/null | awk '
        /^  Class descriptor/ { cls = $4 }
        /^      name          :/ { name = $3 }
        /^      registers     :/ { regs = $3 }
        /^      insns size    :/ { if (cls ~ /Lcom\/weatherwidget\//) print $4, regs, cls "." name }
    '
done | sort -k1,1nr > "$WORK/methods.txt"

total=$(wc -l < "$WORK/methods.txt")
over=$(awk -v mi="$MAX_INSNS" -v mr="$MAX_REGS" '$1 > mi || $2 > mr' "$WORK/methods.txt")
count=$(printf '%s' "$over" | grep -c . || true)

fmt() { sed -E "s/'//g; s/Lcom\/weatherwidget\///; s/;\././" | awk '{ printf "  %7d insns %4d regs  %s\n", $1, $2, $3 }'; }

echo "dex budget: $count of $total com.weatherwidget methods over ${MAX_INSNS} insns or ${MAX_REGS} registers (report-only)"
[ "$count" -gt 0 ] && printf '%s\n' "$over" | fmt
if [ "$TOP" -gt 0 ]; then
    echo "dex budget: top $TOP by instructions"
    head -"$TOP" "$WORK/methods.txt" | fmt
fi
exit 0
