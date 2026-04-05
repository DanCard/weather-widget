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
UNIT_SUMMARY_MONITOR_PID=""

cleanup() {
    if [ -n "$UNIT_SUMMARY_MONITOR_PID" ] && kill -0 "$UNIT_SUMMARY_MONITOR_PID" 2>/dev/null; then
        kill "$UNIT_SUMMARY_MONITOR_PID" 2>/dev/null || true
    fi
    :  # logs kept under $LOG_DIR for post-mortem debugging
}

trap cleanup EXIT

show_help() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Run unit tests and emulator tests in parallel, staggered to avoid build contention.

Options:
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

start_unit_summary_monitor() {
    local log_file=$1
    # Match summaries like "172 medium tests: 1 failed." or "665 tests passed"
    # Also match failure details like "  ✗ Class > Method"
    local summary_pattern='^[0-9]+ (short|medium|long) tests (passed|: [0-9]+ failed\.)|^[0-9]+ tests (passed|, [0-9]+ failed)|^[[:space:]]*✗'
    
    tail -n 0 -F "$log_file" 2>/dev/null | grep --line-buffered -E "$summary_pattern" | while read -r line; do
        if [[ "$line" == *"failed"* ]] || [[ "$line" == *"✗"* ]]; then
            echo -e "${RED}${line}${NC}"
        else
            echo -e "${GREEN}${line}${NC}"
        fi
    done &
    UNIT_SUMMARY_MONITOR_PID=$!
}

TOTAL_START=$(date +%s)
BUILD_START=$(date +%s)

# Start unit tests (this will start the first Gradle build)
# We use --log-file to keep output clean while allowing us to monitor progress.
# Redirect stdout to /dev/null because summaries are teed to the log file and handled by our monitor.
touch "$UNIT_LOG_FILE"
start_unit_summary_monitor "$UNIT_LOG_FILE"
"$UNIT_SCRIPT" --single-invocation --log-file "$UNIT_LOG_FILE" >/dev/null 2>&1 &
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

# Start emulator tests (now that unit test transformations are done)
# echo -e "${BLUE}Starting emulator tests...${NC}"
# We stream emulator tests but we want to filter out the noise.
# For now, let's just let it print its normal condensed output to stdout,
# but also capture everything in the log.
"$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" | tee "$EMULATOR_LOG_FILE" &
EMULATOR_PID=$!

wait "$UNIT_PID"
UNIT_STATUS=$?
if [ -n "$UNIT_SUMMARY_MONITOR_PID" ] && kill -0 "$UNIT_SUMMARY_MONITOR_PID" 2>/dev/null; then
    kill "$UNIT_SUMMARY_MONITOR_PID" 2>/dev/null || true
fi

wait "$EMULATOR_PID"
EMULATOR_STATUS=$?

TOTAL_DURATION=$(( $(date +%s) - TOTAL_START ))

if [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
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
