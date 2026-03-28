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

cleanup() {
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

prefix_output() {
    local label="$1"
    local color="$2"
    while IFS= read -r line; do
        printf "%b[%s]%b %s\n" "$color" "$label" "$NC" "$line"
    done
}

stream_with_prefix() {
    local label="$1"
    local color="$2"
    local log_file="$3"

    tee "$log_file" | prefix_output "$label" "$color"
}

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

echo -e "${BLUE}Clearing ASM cache...${NC}"
clear_asm_cache

# Phase 1: Boot emulator in background (no Gradle involved yet)
echo -e "${BLUE}Phase 1: Booting emulator...${NC}"
"$EMULATOR_SCRIPT" -b "${EMULATOR_ARGS[@]}" >"$EMULATOR_BOOT_LOG" 2>&1 &
BOOT_PID=$!

# Phase 2: Start unit tests (this will start the first Gradle build)
echo -e "${BLUE}Phase 2: Starting unit tests build...${NC}"
# Use --single-invocation and --stream to reduce Gradle process count and allow log monitoring
"$UNIT_SCRIPT" --single-invocation --stream \
    > >(stream_with_prefix "unit" "$GREEN" "$UNIT_LOG_FILE") \
    2> >(stream_with_prefix "unit" "$GREEN" "$UNIT_LOG_FILE" >&2) &
UNIT_PID=$!

# Phase 3: Wait for unit tests to reach execution phase
echo -e "${YELLOW}Waiting for unit test build to finish before starting emulator tests...${NC}"

# Wait for log file to be created
while [ ! -f "$UNIT_LOG_FILE" ]; do
    sleep 0.5
done

BUILD_DONE=false
while kill -0 "$UNIT_PID" 2>/dev/null; do
    # Look for the start of the first test task
    if grep -q "> Task :app:test" "$UNIT_LOG_FILE"; then
        BUILD_DONE=true
        echo -e "${GREEN}Unit test build finished (tests starting).${NC}"
        # Give it a tiny bit more time to finish all transformations if they are parallel
        sleep 2
        break
    fi
    sleep 1
done

if [ "$BUILD_DONE" = false ] && ! kill -0 "$UNIT_PID" 2>/dev/null; then
    echo -e "${YELLOW}Unit test process finished early (perhaps already built or failed).${NC}"
fi

# Phase 4: Start emulator tests (now that unit test transformations are done)
echo -e "${BLUE}Phase 4: Starting emulator tests...${NC}"
"$EMULATOR_SCRIPT" --no-retry "${EMULATOR_ARGS[@]}" \
    > >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE") \
    2> >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE" >&2) &
EMULATOR_PID=$!

echo -e "${BLUE}Both test suites are now active.${NC}"

wait "$UNIT_PID"
UNIT_STATUS=$?

wait "$EMULATOR_PID"
EMULATOR_STATUS=$?

wait "$BOOT_PID" || true # Boot script should be done by now

TOTAL_DURATION=$(( $(date +%s) - TOTAL_START ))

if [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
    echo -e "${GREEN}Both unit tests and emulator tests passed${NC} (${TOTAL_DURATION}s)"
    exit 0
fi

if [ "$UNIT_STATUS" -ne 0 ]; then
    echo -e "${RED}Unit tests failed with exit code $UNIT_STATUS${NC}"
fi

if [ "$EMULATOR_STATUS" -ne 0 ]; then
    echo -e "${RED}Emulator tests failed with exit code $EMULATOR_STATUS${NC}"
fi

echo -e "${RED}Tests failed${NC} (${TOTAL_DURATION}s)"
exit 1
