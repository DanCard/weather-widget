#!/bin/bash
#
# Device check: the battery screen-on observation loop's alarm survives Battery Saver.
#
# Pixel 7 Pro, 2026-10-03: Adaptive Battery Saver turns on whenever the phone is unplugged, and its
# force_all_apps_standby deferred a plain setWindow alarm by +364 days (`dumpsys alarm`:
# `policyWhenElapsed … battery_saver=+364d`), so the 20-minute loop never ran off-charger.
# BatteryObservationAlarm now uses setAndAllowWhileIdle. This checks the system's own verdict.
#
# Why a host script and not an instrumentation test: instrumentation runs inside the app's process,
# which keeps the app "active" for the whole test, and active apps are exempt from the policy. Even a
# plain alarm armed from the test was not deferred (battery_saver=-14ms), so such a test proves
# nothing. Here the real installed app arms its own alarm; we wait until it is no longer active, then
# switch Battery Saver on, which re-evaluates every alarm. Verified both ways on emulator-5554: the
# setAndAllowWhileIdle build passes; a setWindow build fails with battery_saver=+364d.
#
# Full Battery Saver (`cmd power set-mode 1`) stands in for the Pixel's adaptive mode: the emulator's
# stock adaptive policy has force_all_apps_standby=false.
#
# Usage: scripts/check-battery-saver-alarm.sh [-s SERIAL]    (default: first emulator)
# Needs the debug app installed (./gradlew installDebug). Takes about 1-2 minutes.
# Plan: performance/261003-observations-every-20-min-on-battery-screen-on.md

set -u

PKG=com.weatherwidget
LOOP_TAG="tag=*alarm*:${PKG}/.widget.BatteryObservationAlarmReceiver"
ADB_BIN="${ADB:-$HOME/.Android/Sdk/platform-tools/adb}"
[ -x "$ADB_BIN" ] || ADB_BIN="$(command -v adb)"

SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$SERIAL" ]; then
    SERIAL=$("$ADB_BIN" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "No emulator connected (pass -s SERIAL)." >&2; exit 2; }
adb_s() { "$ADB_BIN" -s "$SERIAL" "$@"; }

adb_s shell pm path "$PKG" >/dev/null 2>&1 || { echo "$PKG is not installed on $SERIAL (./gradlew installDebug)." >&2; exit 2; }
UID_NUM=$(adb_s shell cmd package list packages -U "$PKG" | awk -v p="package:$PKG" '$1==p{sub("uid:","",$2); print $2}' | tr -d '\r')
APP_ID="u0a$((UID_NUM - 10000))"
OLD_TIMEOUT=$(adb_s shell settings get system screen_off_timeout | tr -d '\r')

cleanup() {
    adb_s shell cmd power set-mode 0 >/dev/null 2>&1
    adb_s shell dumpsys battery reset >/dev/null 2>&1
    adb_s shell settings put system screen_off_timeout "$OLD_TIMEOUT" >/dev/null 2>&1
}
trap cleanup EXIT

# The `battery_saver=` term of our pending loop alarm, e.g. "+364d23h…", "-14ms" or "--".
loop_policy() {
    adb_s shell dumpsys alarm | tr -d '\r' | awk -v tag="$LOOP_TAG" '
        index($0, tag) { found = 1; next }
        found && /policyWhenElapsed:/ { if (match($0, /battery_saver=[^ ]+/)) print substr($0, RSTART + 14, RLENGTH - 14); exit }'
}
app_is_active() { adb_s shell dumpsys alarm | tr -d '\r' | grep -m1 "Active uids:" | grep -qw "$APP_ID"; }
is_deferred() { [[ "$1" == +*d* ]]; }

echo "Device: $SERIAL  app uid: $UID_NUM ($APP_ID)"

# 1. Unplugged at 75% with the screen staying on, and the app's process alive (its screen receiver
#    is registered at runtime, so it must be running to see the screen turn on).
adb_s shell dumpsys battery unplug
adb_s shell dumpsys battery set level 75
adb_s shell dumpsys battery set status 3
adb_s shell settings put system screen_off_timeout 1800000
adb_s shell input keyevent KEYCODE_WAKEUP
if ! adb_s shell pidof "$PKG" >/dev/null; then
    adb_s shell am start -n "$PKG/.ui.MainActivity" >/dev/null
    sleep 3
fi
adb_s shell input keyevent KEYCODE_HOME

# 2. Cycle the screen so the app arms the loop alarm itself (Battery Saver still off).
adb_s shell input keyevent KEYCODE_SLEEP
sleep 2
adb_s shell input keyevent KEYCODE_WAKEUP

POLICY=""
for _ in $(seq 1 60); do
    POLICY=$(loop_policy)
    [ -n "$POLICY" ] && break
    sleep 1
done
[ -n "$POLICY" ] || { echo "FAIL: the app did not arm the loop alarm within 60 s of screen-on." >&2; exit 1; }
echo "Loop alarm armed (battery_saver=$POLICY, saver off)."

# 3. Wait until the app is no longer active, THEN turn Battery Saver on. The emulator does not
#    re-evaluate an alarm armed while the app was active when the app merely goes idle (a setWindow
#    build passed when the order was reversed), but switching Battery Saver on re-evaluates every
#    alarm against the current state.
for _ in $(seq 1 120); do
    app_is_active || break
    sleep 1
done
if app_is_active; then
    echo "FAIL: the app stayed active for 2 minutes; the policy cannot be observed." >&2
    exit 1
fi
adb_s shell cmd power set-mode 1
for _ in $(seq 1 20); do
    adb_s shell dumpsys alarm | grep -q "Force all apps standby: true" && break
    sleep 0.5
done
adb_s shell dumpsys alarm | grep -q "Force all apps standby: true" || { echo "FAIL: Battery Saver did not force apps into standby." >&2; exit 1; }
sleep 2
POLICY=$(loop_policy)
CONTROL_COUNT=$(adb_s shell dumpsys alarm | tr -d '\r' | grep -cE "battery_saver=\+[0-9]+d")

echo "App inactive, Battery Saver on. Loop alarm battery_saver=${POLICY:-<missing>}; other alarms deferred: $CONTROL_COUNT"

# 4. Control: the policy must be visibly deferring someone, or this run proves nothing.
if [ "$CONTROL_COUNT" -eq 0 ]; then
    echo "FAIL (inconclusive): no alarm on the device is deferred by Battery Saver, so the policy is not in effect." >&2
    exit 1
fi
if [ -z "$POLICY" ]; then
    echo "FAIL: the loop alarm disappeared while waiting." >&2
    exit 1
fi
if is_deferred "$POLICY"; then
    echo "FAIL: the loop alarm is deferred by Battery Saver (battery_saver=$POLICY)." >&2
    exit 1
fi
echo "PASS: the loop alarm is not deferred by Battery Saver."
