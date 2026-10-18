#!/bin/bash
#
# Run unit tests and emulator tests in a staggered parallel fashion.
#
# Usage:
#   ./scripts/staggered-tests.sh
#

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
UNIT_SCRIPT="$SCRIPT_DIR/unit-tests.sh"
# shellcheck source=lib/proc-tree.sh
source "$SCRIPT_DIR/lib/proc-tree.sh"
EMULATOR_SCRIPT="$SCRIPT_DIR/emulator-tests.sh"
LOG_DIR="$PROJECT_DIR/logs/staggered-tests"
mkdir -p "$LOG_DIR"
find "$LOG_DIR" -type f -mtime +14 -delete 2>/dev/null
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
UNIT_LOG_FILE="$LOG_DIR/unit-${TIMESTAMP}.log"
EMULATOR_LOG_FILE="$LOG_DIR/emulator-${TIMESTAMP}.log"
EMULATOR_BOOT_LOG="$LOG_DIR/emulator-boot-${TIMESTAMP}.log"
# The run's timeline: phase starts/ends with elapsed time, plus every summary line the unit and
# emulator scripts print. The unit log is ~30k lines of per-test Gradle output, and summary lines
# appended into it can land mid-line ("...PASSEDShort bucket build finished"); this file is the
# readable record of a run.
SUMMARY_LOG_FILE="$LOG_DIR/summary-${TIMESTAMP}.log"
: > "$SUMMARY_LOG_FILE"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

EMULATOR_ARGS=()
INSTALL_MODE=false
SEQUENTIAL=false

show_help() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Run unit tests and emulator tests in parallel, staggered to avoid build contention.

Options:
  --install                 Also install debug APK to all connected devices
  --sequential              Start emulator tests only after ALL unit tests finish (the old order).
                            Default with 2+ emulators: start them during the Long bucket's tail.
  --emulator-args "ARGS"   Extra arguments forwarded to scripts/emulator-tests.sh
  -h, --help               Show this help
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --emulator-args)
            if [ $# -lt 2 ]; then
                echo -e "${RED}Error: --emulator-args requires a value${NC}"
                exit 1
            fi
            # shellcheck disable=SC2206
            EXTRA_EMULATOR_ARGS=($2)
            EMULATOR_ARGS+=("${EXTRA_EMULATOR_ARGS[@]}")
            shift 2
            ;;
        --install)
            INSTALL_MODE=true
            shift
            ;;
        --sequential)
            SEQUENTIAL=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo -e "${RED}Error: Unknown option '$1'${NC}"
            show_help
            exit 1
            ;;
    esac
done

cd "$PROJECT_DIR"

ASM_CACHE_DIRS=(
    "$PROJECT_DIR/app/build/intermediates/classes/debug/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/classes/debugAndroidTest/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/incremental/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/incremental/transformDebugAndroidTestClassesWithAsm"
)

clear_asm_cache() {
    rm -rf "${ASM_CACHE_DIRS[@]}"
    for dir in "${ASM_CACHE_DIRS[@]}"; do
        find "$dir" -name "*.lock" -delete 2>/dev/null || true
    done
}

# Max attempts per emulator before giving up on a transient device disconnect.
EMULATOR_MAX_ATTEMPTS=3

# Regex of UTP/ddmlib failures that mean "device dropped, not a real test failure" — safe to retry.
TRANSIENT_DISCONNECT_REGEX="device offline|Test run failed to complete|Failed to retrieve additional test outputs|Device/test runner disconnected"

# Wait until an emulator is genuinely ready for instrumentation: ADB state "device", boot
# completed, and responsive to a trivial shell. Recovers "offline" devices first via
# `adb reconnect`. Two emulators on one host frequently flip the second to "offline" right as
# its back-to-back APK installs begin, which makes UTP enumerate 0 tests and fail the run.
wait_for_emulator_ready() {
    local adb_bin="$1" serial="$2" timeout_s="${3:-90}"
    local waited=0
    "$adb_bin" reconnect offline >/dev/null 2>&1 || true
    "$adb_bin" -s "$serial" wait-for-device >/dev/null 2>&1 || true
    while [ "$waited" -lt "$timeout_s" ]; do
        local state boot
        state=$("$adb_bin" -s "$serial" get-state 2>/dev/null | tr -d '\r\n')
        boot=$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
        if [ "$state" = "device" ] && [ "$boot" = "1" ] && "$adb_bin" -s "$serial" shell true >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# Run emulator-tests.sh against a single serial, gating on readiness and retrying transient
# device-offline/disconnect failures (with active `adb reconnect` recovery between attempts).
run_one_emulator_with_retries() {
    local adb_bin="$1" serial="$2"
    local serial_log="$LOG_DIR/emulator-${serial}-${TIMESTAMP}.log"
    local attempt=1
    local serial_status=1
    while [ "$attempt" -le "$EMULATOR_MAX_ATTEMPTS" ]; do
        if ! wait_for_emulator_ready "$adb_bin" "$serial" 90; then
            echo -e "${YELLOW}Warning: ${serial} did not report ready before attempt ${attempt}; proceeding anyway${NC}"
        fi
        : > "$serial_log"  # per-attempt log so the transient-failure grep reflects only this attempt
        EMULATOR_TESTS_TARGET_SERIAL="$serial" "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee -a "$EMULATOR_LOG_FILE" "$serial_log" | to_summary
        serial_status=${PIPESTATUS[0]}
        [ "$serial_status" -eq 0 ] && break
        if [ "$attempt" -lt "$EMULATOR_MAX_ATTEMPTS" ] &&
            grep -qiE "$TRANSIENT_DISCONNECT_REGEX" "$serial_log"; then
            echo -e "${YELLOW}Transient emulator/runner disconnect on ${serial} (attempt ${attempt}/${EMULATOR_MAX_ATTEMPTS}); recovering device and retrying...${NC}"
            "$adb_bin" reconnect offline >/dev/null 2>&1 || true
            sleep 3
            attempt=$((attempt + 1))
            continue
        fi
        # Non-transient failure (real test/build failure) or out of attempts: stop retrying.
        break
    done
    return "$serial_status"
}

# Run every connected emulator in one parallel pass, with readiness gating and
# transient-disconnect retry preserved at whole-run granularity.
#
# This delegates to emulator-tests.sh's OWN multi-emulator mode (reached by NOT setting
# EMULATOR_TESTS_TARGET_SERIAL) instead of launching one wrapper per serial. The
# distinction is the whole reason this is safe: that mode runs
# `assembleDebug assembleDebugAndroidTest` ONCE and then only overlaps `adb install` and
# `am instrument`, so two Gradle builds never touch the project concurrently. Running one
# wrapper per serial in parallel would do exactly that, which is the concurrent
# Kotlin/Java compilation that made earlier parallel attempts flaky.
run_all_emulators_parallel_with_retries() {
    local adb_bin="$1"; shift
    local serials=("$@")
    local attempt=1
    local run_status=1
    while [ "$attempt" -le "$EMULATOR_MAX_ATTEMPTS" ]; do
        local serial
        for serial in "${serials[@]}"; do
            if ! wait_for_emulator_ready "$adb_bin" "$serial" 90; then
                echo -e "${YELLOW}Warning: ${serial} did not report ready before attempt ${attempt}; proceeding anyway${NC}"
            fi
        done
        : > "$EMULATOR_LOG_FILE"  # per-attempt, so the transient grep sees only this attempt
        "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee -a "$EMULATOR_LOG_FILE" | to_summary
        run_status=${PIPESTATUS[0]}
        [ "$run_status" -eq 0 ] && break
        if [ "$attempt" -lt "$EMULATOR_MAX_ATTEMPTS" ] &&
            grep -qiE "$TRANSIENT_DISCONNECT_REGEX" "$EMULATOR_LOG_FILE"; then
            echo -e "${YELLOW}Transient emulator/runner disconnect (attempt ${attempt}/${EMULATOR_MAX_ATTEMPTS}); recovering devices and retrying...${NC}"
            "$adb_bin" reconnect offline >/dev/null 2>&1 || true
            sleep 3
            attempt=$((attempt + 1))
            continue
        fi
        # Non-transient failure (real test/build failure) or out of attempts.
        break
    done
    return "$run_status"
}

# Run the emulator phase; within it the emulators run in parallel. Running it beside the FULL
# JVM load (all four buckets, 16 Robolectric JVMs) once produced truncated per-emulator logs
# with no OK/FAILURES footer, so it never starts before Short, Medium and Localization finish.
# With 2+ emulators it does start during the Long bucket's tail (OVERLAP below): measured
# 2026-10-13, 100 s and 90 s vs 119 s sequential, footers intact; --sequential restores the
# old order.
run_emulator_tests_for_staggered() {
    local status=0
    local adb_bin="${ADB:-$HOME/.Android/Sdk/platform-tools/adb}"
    if [ ! -x "$adb_bin" ]; then
        adb_bin="$(command -v adb || true)"
    fi
    mapfile -t connected_emulators < <("$adb_bin" devices 2>/dev/null | awk '/^emulator-[0-9]+\tdevice$/{print $1}' | sort -V)
    if [ "${#connected_emulators[@]}" -gt 1 ]; then
        echo -e "${BLUE}Detected ${#connected_emulators[@]} connected emulators; building once, then running them in parallel: ${connected_emulators[*]}${NC}"
        run_all_emulators_parallel_with_retries "$adb_bin" "${connected_emulators[@]}"
        status=$?
    elif [ "${#connected_emulators[@]}" -eq 1 ]; then
        for serial in "${connected_emulators[@]}"; do
            echo -e "${YELLOW}=== Running emulator tests on ${serial} ===${NC}"
            run_one_emulator_with_retries "$adb_bin" "$serial"
            local serial_status=$?
            if [ "$serial_status" -ne 0 ]; then
                status=$serial_status
                break
            fi
        done
    else
        "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee "$EMULATOR_LOG_FILE" | to_summary
        status=${PIPESTATUS[0]}
    fi
    return "$status"
}

TOTAL_START=$(date +%s)
BUILD_START=$(date +%s)

# "[+12s 09:36:53] message" — to the terminal and the summary log.
phase() {
    local now
    now=$(date +%s)
    local line
    line="[+$((now - TOTAL_START))s $(date +%H:%M:%S)] $*"
    echo -e "${BLUE}${line}${NC}"
    echo "$line" >> "$SUMMARY_LOG_FILE"
}

# Copy a stream to the terminal unchanged and, stripped of colour, to the summary log.
to_summary() {
    tee >(sed -u 's/\x1b\[[0-9;]*m//g' >> "$SUMMARY_LOG_FILE")
}

resolve_adb() {
    local adb_bin="${ADB:-$HOME/.Android/Sdk/platform-tools/adb}"
    [ -x "$adb_bin" ] || adb_bin="$(command -v adb || true)"
    echo "$adb_bin"
}

# Overlap (2026-10-13): the Long bucket runs ~30-40 s after every other bucket has finished, and
# the emulator phase (~40 s) used to start only after it. With 2+ emulators, the unit-test Gradle
# run also builds both APKs, and emulator tests start once those exist and Short, Medium and
# Localization have reported — so they overlap only Long's tail, never the full 16-JVM peak that
# once truncated emulator logs. One emulator keeps the old order: that path builds through Gradle
# itself. --sequential restores the old order everywhere.
OVERLAP=false
if [ "$SEQUENTIAL" = false ]; then
    overlap_adb=$(resolve_adb)
    if [ -n "$overlap_adb" ] && [ "$("$overlap_adb" devices 2>/dev/null | grep -cE '^emulator-[0-9]+'$'\t''device$')" -ge 2 ]; then
        OVERLAP=true
    fi
fi
UNIT_ARGS=(--log-file "$UNIT_LOG_FILE")
[ "$OVERLAP" = true ] && UNIT_ARGS+=(--also-build-apks)

phase "Unit tests started (log: $UNIT_LOG_FILE)$([ "$OVERLAP" = true ] && echo "; emulators will start during the Long bucket")"

# Start unit tests (this will start the first Gradle build)
# --log-file captures full output for diagnostics; concise summary goes to stdout.
#
# NOTE: We deliberately do NOT pass --install to unit-tests.sh here even when the user
# passed --install to this script. unit-tests.sh's --install triggers `./gradlew installDebug`
# inside the unit-test Gradle invocation, which broadcasts adb install to ALL connected
# devices — including the emulators that emulator-tests.sh is concurrently running
# am instrument on. The mid-test install force-stops com.weatherwidget.test, the
# instrumentation dies silently, and emulator-tests.sh reports the non-descriptive
# "One or more emulators failed". We defer installDebug to a final step below so it
# runs after both test phases finish.
touch "$UNIT_LOG_FILE"

# Ctrl-C: the unit and early emulator phases run with `&`, which starts them with SIGINT ignored,
# so without this they kept running after the script exited (user, 2026-10-18). SIGTERM them.
on_interrupt() {
    trap - INT TERM
    echo -e "\n${YELLOW}Interrupted — stopping unit and emulator tests.${NC}"
    # shellcheck disable=SC2046  # one pid per word
    stop_tree $(own_children)
    phase "Interrupted"
    exit 130
}
trap on_interrupt INT TERM

"$UNIT_SCRIPT" "${UNIT_ARGS[@]}" > >(to_summary) &
UNIT_PID=$!

# Wait for unit tests to reach execution phase
# echo -en "${YELLOW}Waiting for unit test build to finish...${NC}  "

# Wait for log file to be created
while [ ! -f "$UNIT_LOG_FILE" ] && kill -0 "$UNIT_PID" 2>/dev/null; do
    sleep 0.5
done

BUILD_DONE=false
if [ -f "$UNIT_LOG_FILE" ]; then
    while kill -0 "$UNIT_PID" 2>/dev/null; do
        # Look for the start of the first test task
        if grep -q "> Task :app:test" "$UNIT_LOG_FILE"; then
            BUILD_DONE=true
            BUILD_DURATION=$(( $(date +%s) - BUILD_START ))
            phase "Unit test build finished in ${BUILD_DURATION}s"
            # Give it a tiny bit more time to finish all transformations if they are parallel
            sleep 2
            break
        fi
        sleep 1
    done
fi

if [ "$BUILD_DONE" = false ] && ! kill -0 "$UNIT_PID" 2>/dev/null; then
    echo -e "${YELLOW}Unit test build finished early or failed.${NC}"
fi

EMULATOR_STARTED_EARLY=false
EMULATOR_PID=""
if [ "$OVERLAP" = true ]; then
    # Ready = both APKs built by the unit run, and every bucket except Long has reported.
    overlap_ready() {
        grep -qE "^> Task :app:assembleDebug( |$)" "$UNIT_LOG_FILE" 2>/dev/null &&
            grep -qE "^> Task :app:assembleDebugAndroidTest( |$)" "$UNIT_LOG_FILE" 2>/dev/null &&
            grep -qE "short tests" "$SUMMARY_LOG_FILE" 2>/dev/null &&
            grep -qE "medium tests" "$SUMMARY_LOG_FILE" 2>/dev/null &&
            grep -qE "localization tests" "$SUMMARY_LOG_FILE" 2>/dev/null
    }
    while kill -0 "$UNIT_PID" 2>/dev/null && ! overlap_ready; do
        sleep 1
    done
    if kill -0 "$UNIT_PID" 2>/dev/null && ! grep -qE "tests: .*failed|FAILED" "$SUMMARY_LOG_FILE" 2>/dev/null; then
        phase "Emulator tests started during the Long bucket (log: $EMULATOR_LOG_FILE)"
        (
            export EMULATOR_TESTS_PREBUILT=1
            run_emulator_tests_for_staggered
        ) &
        EMULATOR_PID=$!
        EMULATOR_STARTED_EARLY=true
    fi
fi

wait "$UNIT_PID"
UNIT_STATUS=$?
phase "Unit tests finished (exit $UNIT_STATUS)"

EMULATOR_STATUS=0
if [ "$EMULATOR_STARTED_EARLY" = true ]; then
    wait "$EMULATOR_PID"
    EMULATOR_STATUS=$?
    phase "Emulator tests finished (exit $EMULATOR_STATUS)"
elif [ "$UNIT_STATUS" -eq 0 ]; then
    # Overlap planned but unit tests ended first (all cached, or quicker than the APK build):
    # the APKs were still built by that run.
    [ "$OVERLAP" = true ] && export EMULATOR_TESTS_PREBUILT=1
    phase "Emulator tests started (log: $EMULATOR_LOG_FILE)"
    run_emulator_tests_for_staggered
    EMULATOR_STATUS=$?
    phase "Emulator tests finished (exit $EMULATOR_STATUS)"
else
    echo -e "${YELLOW}Skipping emulator tests because unit tests failed.${NC}"
fi

# Deferred installDebug: only fires when both test phases passed AND --install was requested.
# See the NOTE above the unit-tests invocation for why we run install here instead of letting
# unit-tests.sh do it concurrently.
INSTALL_STATUS=0
if [ "$INSTALL_MODE" = true ] && [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
    phase "Deferred installDebug started"
    INSTALL_LOG_FILE="$LOG_DIR/install-${TIMESTAMP}.log"
    if JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$PROJECT_DIR/gradlew" installDebug --console=plain > "$INSTALL_LOG_FILE" 2>&1; then
        SUMMARY=$(grep -E "Installed on" "$INSTALL_LOG_FILE" | tail -1)
        phase "installDebug: ${SUMMARY:-OK}"
    else
        INSTALL_STATUS=$?
        echo -e "${RED}installDebug failed (exit $INSTALL_STATUS) — see $INSTALL_LOG_FILE${NC}"
        tail -20 "$INSTALL_LOG_FILE"
    fi
fi

TOTAL_DURATION=$(( $(date +%s) - TOTAL_START ))
phase "Done: unit=$UNIT_STATUS emulator=$EMULATOR_STATUS install=$INSTALL_STATUS total=${TOTAL_DURATION}s (summary: $SUMMARY_LOG_FILE)"

if [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ] && [ "$INSTALL_STATUS" -eq 0 ]; then
    echo -e "${GREEN}Both unit tests and emulator tests passed${NC} (${TOTAL_DURATION}s)"
    exit 0
fi

if [ "$UNIT_STATUS" -ne 0 ]; then
    echo -e "${RED}Unit tests failed with exit code $UNIT_STATUS${NC}"
    echo -e "${RED}Full unit test log:${NC}"
    cat "$UNIT_LOG_FILE"
fi

if [ "$EMULATOR_STATUS" -ne 0 ]; then
    echo -e "${RED}Emulator tests failed with exit code $EMULATOR_STATUS${NC}"
    echo -e "${RED}Full emulator test log:${NC}"
    cat "$EMULATOR_LOG_FILE"
fi

echo -e "${RED}Tests failed${NC} (${TOTAL_DURATION}s)"
exit 1
