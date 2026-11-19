#!/usr/bin/env bash
# ART-verifies an APK or AAB on an emulator without installing it: pushes its dex to
# /data/local/tmp, runs the device's dex2oat with --compiler-filter=verify, and fails on any
# "Verification error" in com.weatherwidget (each rejected method is printed).
#
# Nothing is installed or removed, so the emulator's installed debug app and widgets are untouched,
# and a release build (same application id, different signing key) can be checked beside it.
# A rejected class crashes the app the first time it loads (VerifyError, 2026-11-19); the release
# build is R8-optimized, so its dex differs from debug and needs its own check.
# plans/261119-split-oversized-android-suspend-functions.md
#
# Usage: scripts/verify-apk-dex.sh [-s <emulator-serial>] <app.apk | app.aab>
# Exit: 0 clean, 1 rejected methods, 2 setup error (no emulator, bad input, dex2oat failed).
set -uo pipefail

SERIAL=""
if [ "${1:-}" = "-s" ]; then SERIAL="${2:-}"; shift 2; fi
INPUT="${1:-}"
ADB="${ADB_BIN:-adb}"

if [ -z "$INPUT" ] || [ ! -f "$INPUT" ]; then
    echo "verify-apk-dex: usage: $0 [-s serial] <app.apk|app.aab> (got '${INPUT}')" >&2
    exit 2
fi
if [ -z "$SERIAL" ]; then
    # Emulators only: this never targets a phone unless named with -s.
    SERIAL=$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')
fi
if [ -z "$SERIAL" ]; then
    echo "verify-apk-dex: no running emulator (start one, or pass -s <serial>)" >&2
    exit 2
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CHECK="$WORK/check.apk"
case "$INPUT" in
    *.aab)
        # A bundle keeps its dex under base/dex/; dex2oat wants classes*.dex at the zip root.
        mkdir -p "$WORK/dex"
        if ! unzip -q -o "$INPUT" 'base/dex/classes*.dex' -d "$WORK/dex"; then
            echo "verify-apk-dex: no base/dex/classes*.dex in $INPUT" >&2
            exit 2
        fi
        (cd "$WORK/dex/base/dex" && zip -q -0 "$CHECK" classes*.dex)
        ;;
    *.apk) cp "$INPUT" "$CHECK" ;;
    *) echo "verify-apk-dex: expected .apk or .aab: $INPUT" >&2; exit 2 ;;
esac
DEX_COUNT=$(unzip -l "$CHECK" | grep -c 'classes[0-9]*\.dex$')

REMOTE=/data/local/tmp/verify-apk-dex
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE && mkdir -p $REMOTE" || { echo "verify-apk-dex: cannot prepare $REMOTE on $SERIAL" >&2; exit 2; }
if ! "$ADB" -s "$SERIAL" push "$CHECK" "$REMOTE/check.apk" >/dev/null 2>&1; then
    echo "verify-apk-dex: push to $SERIAL failed" >&2
    exit 2
fi

# One quoted string: adb shell re-splits arguments on the device, which breaks `date`'s format
# (an empty `since` makes logcat -T show only its last line, hiding every error).
SINCE=$("$ADB" -s "$SERIAL" shell "date +'%m-%d %H:%M:%S.000'" | tr -d '\r')
if ! [[ "$SINCE" =~ ^[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}\.000$ ]]; then
    echo "verify-apk-dex: could not read device time on $SERIAL: '$SINCE'" >&2
    exit 2
fi
DEX2OAT_OUT=$("$ADB" -s "$SERIAL" shell "/apex/com.android.art/bin/dex2oat64 --dex-file=$REMOTE/check.apk \
    --dex-location=$REMOTE/check.apk --oat-file=$REMOTE/check.odex --compiler-filter=verify; echo DEX2OAT_EXIT=\$?" 2>&1 | tr -d '\r')
sleep 1
ERRORS=$("$ADB" -s "$SERIAL" logcat -d -T "$SINCE" 2>/dev/null | grep "Verification error" | grep "com\.weatherwidget")
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE" >/dev/null 2>&1

if ! printf '%s' "$DEX2OAT_OUT" | grep -q "DEX2OAT_EXIT=0"; then
    echo "verify-apk-dex: dex2oat failed on $SERIAL:" >&2
    printf '%s\n' "$DEX2OAT_OUT" | tail -5 >&2
    exit 2
fi
if [ -n "$ERRORS" ]; then
    echo "verify-apk-dex: ART rejected methods in $(basename "$INPUT") ($DEX_COUNT dex, $SERIAL):"
    printf '%s\n' "$ERRORS" | sed -E 's/.*Verification error in /  /' | cut -c1-240
    exit 1
fi
echo "verify-apk-dex: $(basename "$INPUT") verified clean ($DEX_COUNT dex files, $SERIAL)"
exit 0
