#!/bin/bash
#
# Run unit tests and emulator tests in parallel.
#
# Usage:
#   ./scripts/parallel-tests.sh
#   ./scripts/parallel-tests.sh --emulator-args "-q -c com.weatherwidget.SomeTest"
#   ./scripts/parallel-tests.sh --emulator-args "-e Medium_Phone_API_36 -d 15m"
#

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
UNIT_SCRIPT="$SCRIPT_DIR/test-unit-by-duration.sh"
EMULATOR_SCRIPT="$SCRIPT_DIR/emulator-tests.sh"
LOG_DIR="$PROJECT_DIR/logs/parallel-tests"
mkdir -p "$LOG_DIR"
find "$LOG_DIR" -type f -mtime +14 -delete 2>/dev/null
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
BUILD_LOG="$LOG_DIR/build-${TIMESTAMP}.log"
UNIT_LOG_FILE="$LOG_DIR/unit-${TIMESTAMP}.log"
EMULATOR_LOG_FILE="$LOG_DIR/emulator-${TIMESTAMP}.log"
UNIT_TAIL_LINES=120

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

Run unit tests and emulator tests in parallel using the existing helper scripts.

Options:
  --emulator-args "ARGS"   Extra arguments forwarded to scripts/emulator-tests.sh
  -h, --help               Show this help

Examples:
  $(basename "$0")
  $(basename "$0") --emulator-args "-q"
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

if [ ! -x "$UNIT_SCRIPT" ]; then
    echo -e "${RED}Error: Unit test script is not executable: $UNIT_SCRIPT${NC}"
    exit 1
fi

if [ ! -x "$EMULATOR_SCRIPT" ]; then
    echo -e "${RED}Error: Emulator test script is not executable: $EMULATOR_SCRIPT${NC}"
    exit 1
fi

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

print_failure_tail() {
    local title="$1"
    local log_file="$2"

    echo -e "${RED}${title}${NC}"
    tail -n "$UNIT_TAIL_LINES" "$log_file"
}

print_unit_report_paths() {
    local found=0
    local report_dir

    for report_dir in "$PROJECT_DIR"/app/build/reports/tests/testShortDebugUnitTestFresh \
        "$PROJECT_DIR"/app/build/reports/tests/testMediumDebugUnitTestFresh \
        "$PROJECT_DIR"/app/build/reports/tests/testLongDebugUnitTestFresh; do
        if [ -f "$report_dir/index.html" ]; then
            if [ "$found" -eq 0 ]; then
                echo "Unit test reports:"
            fi
            echo "  $report_dir/index.html"
            found=1
        fi
    done

    if [ "$found" -eq 0 ]; then
        for report_dir in "$PROJECT_DIR"/app/build/reports/tests/testShortDebugUnitTest \
            "$PROJECT_DIR"/app/build/reports/tests/testMediumDebugUnitTest \
            "$PROJECT_DIR"/app/build/reports/tests/testLongDebugUnitTest; do
            if [ -f "$report_dir/index.html" ]; then
                if [ "$found" -eq 0 ]; then
                    echo "Unit test reports:"
                fi
                echo "  $report_dir/index.html"
                found=1
            fi
        done
    fi
}

ASM_CACHE_DIRS=(
    "$PROJECT_DIR/app/build/intermediates/classes/debug/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/classes/debugAndroidTest/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/incremental/transformDebugClassesWithAsm"
    "$PROJECT_DIR/app/build/intermediates/incremental/transformDebugAndroidTestClassesWithAsm"
)

clear_asm_cache() {
    rm -rf "${ASM_CACHE_DIRS[@]}"
}

# Emulator script defers ASM retries to us (--no-retry → exit 2).
# This avoids 'gradlew clean' racing with the parallel unit-test build.
EMULATOR_NO_RETRY_ARGS=(--no-retry)

# Pre-build: compile and ASM-transform in a single Gradle invocation so the
# parallel test processes that follow all find these tasks UP-TO-DATE.
# Without this, 4 concurrent Gradle daemons race on transformDebugClassesWithAsm,
# corrupting each other's intermediate output.
BUILD_START=$(date +%s)
printf "${BLUE}Pre-building...${NC} "
if JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" --console=plain \
    transformDebugUnitTestClassesWithAsm \
    assembleDebugAndroidTest >"$BUILD_LOG" 2>&1; then
    BUILD_ELAPSED=$(( $(date +%s) - BUILD_START ))
    echo -e "${GREEN}done${NC} (${BUILD_ELAPSED}s)"
else
    echo -e "${RED}failed${NC} (see $BUILD_LOG)"
    tail -20 "$BUILD_LOG"
    exit 1
fi

echo -e "${BLUE}Starting unit tests and emulator tests in parallel${NC}"

set -o pipefail

"$UNIT_SCRIPT" \
    > >(stream_with_prefix "unit" "$GREEN" "$UNIT_LOG_FILE") \
    2> >(stream_with_prefix "unit" "$GREEN" "$UNIT_LOG_FILE" >&2) &
UNIT_PID=$!

"$EMULATOR_SCRIPT" "${EMULATOR_NO_RETRY_ARGS[@]}" "${EMULATOR_ARGS[@]}" \
    > >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE") \
    2> >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE" >&2) &
EMULATOR_PID=$!

wait "$UNIT_PID"
UNIT_STATUS=$?

wait "$EMULATOR_PID"
EMULATOR_STATUS=$?

# Handle ASM retry: emulator exited 2 → clear ASM cache, wait for unit tests, retry emulator
if [ "$EMULATOR_STATUS" -eq 2 ]; then
    echo -e "${YELLOW}Emulator hit ASM error — clearing cache and retrying...${NC}"
    clear_asm_cache

    # Wait for unit tests if still running (avoid build contention during retry)
    if kill -0 "$UNIT_PID" 2>/dev/null; then
        wait "$UNIT_PID" || true
    fi

    "$EMULATOR_SCRIPT" "${EMULATOR_ARGS[@]}" \
        > >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE") \
        2> >(stream_with_prefix "emulator" "$YELLOW" "$EMULATOR_LOG_FILE" >&2) &
    EMULATOR_PID=$!
    wait "$EMULATOR_PID"
    EMULATOR_STATUS=$?
fi

if [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
    echo -e "${GREEN}Both unit tests and emulator tests passed${NC}"
    exit 0
fi

if [ "$UNIT_STATUS" -ne 0 ]; then
    echo -e "${RED}Unit tests failed with exit code $UNIT_STATUS${NC}"
    print_failure_tail "Recent unit test output:" "$UNIT_LOG_FILE"
    print_unit_report_paths
fi

if [ "$EMULATOR_STATUS" -ne 0 ]; then
    echo -e "${RED}Emulator tests failed with exit code $EMULATOR_STATUS${NC}"
    print_failure_tail "Recent emulator test output:" "$EMULATOR_LOG_FILE"
fi

exit 1
