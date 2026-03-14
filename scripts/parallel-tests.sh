#!/bin/bash
#
# Run unit tests and emulator tests in parallel.
#
# Usage:
#   ./scripts/parallel-tests.sh
#   ./scripts/parallel-tests.sh --force-unit
#   ./scripts/parallel-tests.sh --emulator-args "-q -c com.weatherwidget.SomeTest"
#   ./scripts/parallel-tests.sh --force-unit --emulator-args "-e Medium_Phone_API_36 -d 15m"
#

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
UNIT_SCRIPT="$SCRIPT_DIR/unit-tests.sh"
EMULATOR_SCRIPT="$SCRIPT_DIR/emulator-tests.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

UNIT_ARGS=()
EMULATOR_ARGS=()

show_help() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Run unit tests and emulator tests in parallel using the existing helper scripts.

Options:
  --force-unit             Pass --force to scripts/unit-tests.sh
  --emulator-args "ARGS"   Extra arguments forwarded to scripts/emulator-tests.sh
  -h, --help               Show this help

Examples:
  $(basename "$0")
  $(basename "$0") --force-unit
  $(basename "$0") --emulator-args "-q"
  $(basename "$0") --force-unit --emulator-args "-e Medium_Phone_API_36 -d 15m"
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --force-unit)
            UNIT_ARGS+=("--force")
            shift
            ;;
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

echo -e "${BLUE}Starting unit tests and emulator tests in parallel${NC}"

set -o pipefail

"$UNIT_SCRIPT" "${UNIT_ARGS[@]}" \
    > >(prefix_output "unit" "$GREEN") \
    2> >(prefix_output "unit" "$GREEN" >&2) &
UNIT_PID=$!

"$EMULATOR_SCRIPT" "${EMULATOR_ARGS[@]}" \
    > >(prefix_output "emulator" "$YELLOW") \
    2> >(prefix_output "emulator" "$YELLOW" >&2) &
EMULATOR_PID=$!

wait "$UNIT_PID"
UNIT_STATUS=$?

wait "$EMULATOR_PID"
EMULATOR_STATUS=$?

if [ "$UNIT_STATUS" -eq 0 ] && [ "$EMULATOR_STATUS" -eq 0 ]; then
    echo -e "${GREEN}Both unit tests and emulator tests passed${NC}"
    exit 0
fi

if [ "$UNIT_STATUS" -ne 0 ]; then
    echo -e "${RED}Unit tests failed with exit code $UNIT_STATUS${NC}"
fi

if [ "$EMULATOR_STATUS" -ne 0 ]; then
    echo -e "${RED}Emulator tests failed with exit code $EMULATOR_STATUS${NC}"
fi

exit 1
