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
EMULATOR_SCRIPT="$SCRIPT_DIR/emulator-tests.sh"
LOG_DIR="$PROJECT_DIR/logs/staggered-tests"
mkdir -p "$LOG_DIR"
find "$LOG_DIR" -type f -mtime +14 -delete 2>/dev/null
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
UNIT_LOG_FILE="$LOG_DIR/unit-${TIMESTAMP}.log"
EMULATOR_LOG_FILE="$LOG_DIR/emulator-${TIMESTAMP}.log"
EMULATOR_BOOT_LOG="$LOG_DIR/emulator-boot-${TIMESTAMP}.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

EMULATOR_ARGS=()
INSTALL_MODE=false

show_help() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Run unit tests and emulator tests in parallel, staggered to avoid build contention.

Options:
  --install                 Also install debug APK to all connected devices
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

TOTAL_START=$(date +%s)
BUILD_START=$(date +%s)

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
"$UNIT_SCRIPT" --log-file "$UNIT_LOG_FILE" &
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
            echo -en "${GREEN}Unit test build finished in ${BUILD_DURATION}s.${NC}  "
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

wait "$UNIT_PID"
UNIT_STATUS=$?

# Start emulator tests only after JVM/unit tests finish. Running the long JVM bucket while
# two emulators are both installing and executing instrumentation has produced truncated
# per-emulator logs with no OK/FAILURES footer, even though standalone emulator-tests.sh
# passes. Keep the phases sequential so emulator-tests.sh owns the device-intensive phase.
run_emulator_tests_for_staggered() {
    local status=0
    local adb_bin="${ADB:-$HOME/.Android/Sdk/platform-tools/adb}"
    if [ ! -x "$adb_bin" ]; then
        adb_bin="$(command -v adb || true)"
    fi
    mapfile -t connected_emulators < <("$adb_bin" devices 2>/dev/null | awk '/^emulator-[0-9]+\tdevice$/{print $1}' | sort -V)
    if [ "${#connected_emulators[@]}" -gt 1 ]; then
        echo -e "${BLUE}Detected ${#connected_emulators[@]} connected emulators; running emulator tests sequentially for staggered reliability: ${connected_emulators[*]}${NC}"
        for serial in "${connected_emulators[@]}"; do
            echo -e "${YELLOW}=== Running emulator tests on ${serial} ===${NC}"
            local serial_log="$LOG_DIR/emulator-${serial}-${TIMESTAMP}.log"
            : > "$serial_log"
            EMULATOR_TESTS_TARGET_SERIAL="$serial" "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee -a "$EMULATOR_LOG_FILE" "$serial_log"
            local serial_status=${PIPESTATUS[0]}
            if [ "$serial_status" -ne 0 ] &&
                grep -qiE "device offline|Test run failed to complete|Failed to retrieve additional test outputs|Device/test runner disconnected" "$serial_log"; then
                echo -e "${YELLOW}Transient emulator/runner disconnect on ${serial}; retrying once...${NC}"
                EMULATOR_TESTS_TARGET_SERIAL="$serial" "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee -a "$EMULATOR_LOG_FILE" "$serial_log"
                serial_status=${PIPESTATUS[0]}
            fi
            if [ "$serial_status" -ne 0 ]; then
                status=$serial_status
                break
            fi
        done
    else
        "$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee "$EMULATOR_LOG_FILE"
        status=${PIPESTATUS[0]}
    fi
    return "$status"
}

EMULATOR_STATUS=0
if [ "$UNIT_STATUS" -eq 0 ]; then
    run_emulator_tests_for_staggered
    EMULATOR_STATUS=$?
else
    echo -e "${YELLOW}Skipping emulator tests because unit tests failed.${NC}"
fi

# Deferred installDebug: only fires when both test phases passed AND --install was requested.
# See the NOTE above the unit-tests invocation for why we run install here instead of letting
# unit-tests.sh do it concurrently.
INSTALL_STATUS=0
if [ "$INSTALL_MODE" = true ] && [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
    echo -e "${YELLOW}Running deferred installDebug to all connected devices...${NC}"
    INSTALL_LOG_FILE="$LOG_DIR/install-${TIMESTAMP}.log"
    if JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$PROJECT_DIR/gradlew" installDebug --console=plain > "$INSTALL_LOG_FILE" 2>&1; then
        SUMMARY=$(grep -E "Installed on" "$INSTALL_LOG_FILE" | tail -1)
        echo -e "${GREEN}installDebug: ${SUMMARY:-OK}${NC}"
    else
        INSTALL_STATUS=$?
        echo -e "${RED}installDebug failed (exit $INSTALL_STATUS) — see $INSTALL_LOG_FILE${NC}"
        tail -20 "$INSTALL_LOG_FILE"
    fi
fi

TOTAL_DURATION=$(( $(date +%s) - TOTAL_START ))

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
