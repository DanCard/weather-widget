#!/bin/bash
#
# Run unit tests and show a summary
#

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -en "${BLUE}Running unit tests...${NC} \t"

FORCE_FLAG=""
if [[ "$1" == "--force" ]]; then
    FORCE_FLAG="--rerun-tasks"
    echo -e "${YELLOW}Forcing tests to rerun...${NC}"
fi

# Delete old test results so we never parse stale XML from a previous run
RESULTS_DIR="app/build/test-results/testDebugUnitTest"
rm -rf "$RESULTS_DIR"

# Run gradle and strip blank lines + per-test PASSED lines for a compact view
# We keep failures and other diagnostics visible.
./gradlew :app:test $FORCE_FLAG --console=plain | awk 'NF && $0 !~ / > .* PASSED$/'

EXIT_CODE=${PIPESTATUS[0]}

# Parse results from XML files
TOTAL=0
PASSED=0
FAILED=0
ERRORS=0
SKIPPED=0
FAILED_TESTS=()
declare -A CLASS_PASSED_COUNTS

if [ -d "$RESULTS_DIR" ]; then
    for xml in "$RESULTS_DIR"/TEST-*.xml; do
        if [ -f "$xml" ]; then
            T=$(grep -oP 'tests="\K[0-9]+' "$xml" | head -1)
            F=$(grep -oP 'failures="\K[0-9]+' "$xml" | head -1)
            E=$(grep -oP 'errors="\K[0-9]+' "$xml" | head -1)
            S=$(grep -oP 'skipped="\K[0-9]+' "$xml" | head -1)
            XML_BASENAME=$(basename "$xml")
            SUITE_NAME=${XML_BASENAME#TEST-}
            SUITE_NAME=${SUITE_NAME%.xml}
            CLASS_NAME=${SUITE_NAME##*.}
            CLASS_PASSED=$((T - F - E - S))
            
            TOTAL=$((TOTAL + T))
            FAILED=$((FAILED + F))
            ERRORS=$((ERRORS + E))
            SKIPPED=$((SKIPPED + S))

            if [ "$CLASS_PASSED" -gt 0 ]; then
                CLASS_PASSED_COUNTS["$CLASS_NAME"]=$CLASS_PASSED
            fi
            
            if [ "$F" -gt 0 ] || [ "$E" -gt 0 ]; then
                # Extract failed test names
                while read -r line; do
                    test_name=$(echo "$line" | grep -oP 'name="\K[^"]+')
                    FAILED_TESTS+=("$test_name")
                done < <(grep '<testcase' "$xml" | grep -E '<failure|<error')
            fi
        fi
    done
    PASSED=$((TOTAL - FAILED - ERRORS - SKIPPED))
fi

# echo -e "${BLUE}Unit Test Summary${NC}"

if [ "$TOTAL" -gt 0 ]; then
    if [ "$FAILED" -eq 0 ] && [ "$ERRORS" -eq 0 ]; then
        echo -en "${GREEN}✓ All $TOTAL tests passed${NC} \t"
    else
        echo -e "${RED}  ✗ $FAILED tests failed, $ERRORS errors${NC}"
        echo -e "${RED}Failed tests:${NC}"
        for ft in "${FAILED_TESTS[@]}"; do
            echo -e "  ${RED}✗ $ft${NC}"
        done
    fi
    echo -e "  Total:   $TOTAL"
    echo -e "  ${GREEN}Passed:  $PASSED${NC}"
    if [ "$FAILED" -gt 0 ]; then
        echo -e "  ${RED}Failed:  $FAILED${NC}"
    fi
    if [ "$ERRORS" -gt 0 ]; then
        echo -e "  ${RED}Errors:  $ERRORS${NC}"
    fi
    if [ "$SKIPPED" -gt 0 ]; then
        echo -e "  ${YELLOW}Skipped: $SKIPPED${NC}"
    fi

    if [ "${#CLASS_PASSED_COUNTS[@]}" -gt 0 ]; then
        echo -e "${BLUE}Per-class pass summary:${NC}"
        while IFS= read -r class_name; do
            echo -e "  ${GREEN}${class_name}${NC} > Passed ${CLASS_PASSED_COUNTS[$class_name]} tests"
        done < <(printf '%s\n' "${!CLASS_PASSED_COUNTS[@]}" | sort)
    fi
else
    if [ "$EXIT_CODE" -ne 0 ]; then
        echo -e "${RED}✗ Build failed (no test results produced)${NC}"
    else
        echo -e "${YELLOW}  ⚠ No test results found${NC}"
    fi
fi

exit $EXIT_CODE
