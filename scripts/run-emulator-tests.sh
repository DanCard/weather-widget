#!/bin/bash
#
# Run instrumented tests on an Android emulator
# Automatically launches emulator, runs tests, and shuts down
#
# Usage:
#   ./scripts/run-emulator-tests.sh                    # Use default emulator (visible GUI)
#   ./scripts/run-emulator-tests.sh -q                 # Run headless (quiet/no window)
#   ./scripts/run-emulator-tests.sh -e EMULATOR_NAME   # Use specific emulator
#   ./scripts/run-emulator-tests.sh -c CLASS_NAME      # Run specific test class
#   ./scripts/run-emulator-tests.sh -h                 # Show help
#

set -e  # Exit on error

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEFAULT_EMULATOR="Generic_Foldable_API36"
EMU_TIMEOUT=120  # Seconds to wait for emulator boot
TEST_TIMEOUT=300 # Seconds for tests to complete
VISIBLE_MODE=true  # Run emulator with GUI window (default)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
EMULATOR_NAME=""
TEST_CLASS=""
SHOW_HELP=false

KEEP_EMULATOR=true  # Default: keep emulator running

while getopts "e:c:qsh" opt; do
    case $opt in
        e) EMULATOR_NAME="$OPTARG" ;;
        c) TEST_CLASS="$OPTARG" ;;
        q) VISIBLE_MODE=false ;;
        s) KEEP_EMULATOR=false ;;
        h) SHOW_HELP=true ;;
        *) SHOW_HELP=true ;;
    esac
done

# Show help
if [ "$SHOW_HELP" = true ]; then
    echo "Usage: $(basename "$0") [OPTIONS]"
    echo ""
    echo "Run instrumented tests on an Android emulator"
    echo ""
    echo "Options:"
    echo "  -q             Run headless (no GUI window, default: visible)"
    echo "  -s             Shutdown emulator after tests (default: keep running)"
    echo "  -e EMULATOR    Use specific emulator (default: $DEFAULT_EMULATOR)"
    echo "  -c CLASS       Run specific test class (e.g., com.weatherwidget.WidgetSizeCalculatorTest)"
    echo "  -h             Show this help"
    echo ""
    echo "Examples:"
    echo "  $(basename "$0")                              # Run tests, keep emulator running"
    echo "  $(basename "$0") -s                           # Run tests, shutdown after"
    echo "  $(basename "$0") -qs                          # Headless CI mode, shutdown after"
    echo "  $(basename "$0") -e Medium_Phone_API_36      # Use phone emulator"
    echo "  $(basename "$0") -c WidgetSizeCalculatorTest # Run specific test class"
    exit 0
fi

# Find Android SDK tools
declare -A SDK_PATHS=(
    ["$HOME/.Android/Sdk"]=1
    ["$HOME/Android/Sdk"]=1
    ["/usr/local/android-sdk"]=1
    ["/opt/android-sdk"]=1
)

SDK_ROOT=""
for path in "${!SDK_PATHS[@]}"; do
    if [ -d "$path" ]; then
        SDK_ROOT="$path"
        break
    fi
done

if [ -z "$SDK_ROOT" ]; then
    echo -e "${RED}Error: Android SDK not found${NC}"
    echo "Please set ANDROID_SDK_ROOT or ensure SDK is in standard location"
    exit 1
fi

EMU_BIN="$SDK_ROOT/emulator/emulator"
ADB_BIN="$SDK_ROOT/platform-tools/adb"

if [ ! -f "$EMU_BIN" ]; then
    echo -e "${RED}Error: Emulator binary not found at $EMU_BIN${NC}"
    exit 1
fi

if [ ! -f "$ADB_BIN" ]; then
    echo -e "${RED}Error: ADB binary not found at $ADB_BIN${NC}"
    exit 1
fi

echo -e "${BLUE}Using Android SDK: $SDK_ROOT${NC}"

# List available emulators if none specified
if [ -z "$EMULATOR_NAME" ]; then
    echo ""
    echo -e "${YELLOW}Available emulators:${NC}"
    $EMU_BIN -list-avds | while read -r avd; do
        if [ "$avd" = "$DEFAULT_EMULATOR" ]; then
            echo "  * $avd (default)"
        else
            echo "    $avd"
        fi
    done
    echo ""
    
    EMULATOR_NAME="$DEFAULT_EMULATOR"
    
    # Check if default exists
    if ! $EMU_BIN -list-avds | grep -q "^${EMULATOR_NAME}$"; then
        echo -e "${RED}Error: Default emulator '$EMULATOR_NAME' not found${NC}"
        echo "Use -e flag to specify an emulator from the list above"
        exit 1
    fi
fi

echo -e "${BLUE}Selected emulator: $EMULATOR_NAME${NC}"

# Function to cleanup emulator
cleanup() {
    # Skip cleanup if -k flag was used
    if [ "$KEEP_EMULATOR" = true ]; then
        echo ""
        echo -e "${YELLOW}Keeping emulator running (use -k flag)${NC}"
        echo -e "${GREEN}Emulator serial: $EMULATOR_SERIAL${NC}"
        return
    fi
    
    # Skip cleanup if we used an existing emulator
    if [ "$USE_EXISTING" = true ]; then
        echo ""
        echo -e "${YELLOW}Shutting down existing emulator...${NC}"
    else
        echo ""
        echo -e "${YELLOW}Cleaning up emulator...${NC}"
    fi
    
    # Try graceful shutdown first
    $ADB_BIN emu kill 2>/dev/null || true
    
    # Kill any remaining emulator processes for this AVD
    pkill -f "emulator.*-avd.*$EMULATOR_NAME" 2>/dev/null || true
    
    echo -e "${GREEN}Emulator shutdown complete${NC}"
}

# Set trap to ensure cleanup on exit
trap cleanup EXIT INT TERM

# Check if emulator is already running
echo ""
echo -e "${BLUE}Checking for existing emulators...${NC}"
EXISTING_EMU=$($ADB_BIN devices | grep "emulator-" | cut -f1 | head -1)

if [ -n "$EXISTING_EMU" ]; then
    echo -e "${YELLOW}Found running emulator: $EXISTING_EMU${NC}"
    if [ "$KEEP_EMULATOR" = true ]; then
        echo "Will keep emulator running after tests (default)"
    else
        echo "Will shut down emulator after tests (-s flag)"
    fi
    USE_EXISTING=true
    EMULATOR_SERIAL="$EXISTING_EMU"
else
    USE_EXISTING=false
fi

# Start emulator if needed
if [ "$USE_EXISTING" = false ]; then
    echo ""
    echo -e "${BLUE}Starting emulator...${NC}"
    echo "This may take 30-60 seconds..."
    
    # Build emulator launch options
    EMU_FLAGS="-gpu swiftshader_indirect -no-boot-anim"
    
    if [ "$VISIBLE_MODE" = false ]; then
        EMU_FLAGS="$EMU_FLAGS -no-window -no-audio"
        echo "Mode: Headless (use -q flag)"
    else
        echo "Mode: Visible window"
        echo "Note: Make sure you have a display (X11) available"
    fi
    
    # Launch emulator in background
    # shellcheck disable=SC2086
    nohup "$EMU_BIN" -avd "$EMULATOR_NAME" \
        $EMU_FLAGS \
        > /tmp/emulator_${EMULATOR_NAME}.log 2>&1 &
    
    EMU_PID=$!
    echo "Emulator PID: $EMU_PID"
    
    # Wait for device to appear
    echo ""
    echo -e "${YELLOW}Waiting for device to boot...${NC}"
    
    START_TIME=$(date +%s)
    DEVICE_READY=false
    
    while [ $(($(date +%s) - START_TIME)) -lt $EMU_TIMEOUT ]; do
        EMULATOR_SERIAL=$($ADB_BIN devices | grep "emulator-" | cut -f1 | head -1)
        
        if [ -n "$EMULATOR_SERIAL" ]; then
            # Check if boot is complete
            BOOT_COMPLETED=$($ADB_BIN -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null || echo "")
            
            if [ "$BOOT_COMPLETED" = "1" ]; then
                DEVICE_READY=true
                break
            fi
        fi
        
        # Show progress
        ELAPSED=$(($(date +%s) - START_TIME))
        echo -ne "  Waiting... ${ELAPSED}s elapsed\r"
        sleep 2
    done
    
    if [ "$DEVICE_READY" = false ]; then
        echo ""
        echo -e "${RED}Error: Emulator failed to boot within ${EMU_TIMEOUT} seconds${NC}"
        echo "Check log: tail -f /tmp/emulator_${EMULATOR_NAME}.log"
        exit 1
    fi
    
    echo ""
    echo -e "${GREEN}Emulator ready: $EMULATOR_SERIAL${NC}"
    
    # Additional wait for system stability
    echo "Waiting for system services..."
    sleep 10
fi

# Show device info
echo ""
echo -e "${BLUE}Device info:${NC}"
$ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.product.model 2>/dev/null || echo "  Model: Unknown"
$ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.build.version.release 2>/dev/null || echo "  Android: Unknown"

# Run tests
echo ""
echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  Running Instrumented Tests${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

cd "$PROJECT_DIR"

# Build gradle command
GRADLE_CMD=":app:connectedDebugAndroidTest"
if [ -n "$TEST_CLASS" ]; then
    GRADLE_CMD="$GRADLE_CMD -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS"
    echo -e "${YELLOW}Running test class: $TEST_CLASS${NC}"
else
    echo -e "${YELLOW}Running all instrumented tests${NC}"
fi

# Run tests with timeout - show output in real-time
TEST_START=$(date +%s)
TEST_SUCCESS=false

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Function to show test names as they complete
show_progress() {
    local logfile=$1
    local last_line=0
    while true; do
        if [ -f "$logfile" ]; then
            # Get all new lines since last check
            local total_lines=$(wc -l < "$logfile")
            if [ "$total_lines" -gt "$last_line" ]; then
                # Extract and display new test results
                sed -n "$((last_line + 1)),${total_lines}p" "$logfile" | while read -r line; do
                    # Gradle test output format: "ClassName > testMethod PASSED/FAILED"
                    # or "ClassName.testMethod PASSED/FAILED"
                    if echo "$line" | grep -qE ">.*PASSED|PASSED"; then
                        # Extract test name from various formats
                        local test_name=$(echo "$line" | sed -n 's/.*> \([^ ]*\).*/\1/p')
                        if [ -z "$test_name" ]; then
                            test_name=$(echo "$line" | grep -oE "[a-zA-Z0-9_]+Test\.\w+" | head -1)
                        fi
                        if [ -n "$test_name" ]; then
                            echo -e "${GREEN}  ✓ ${test_name}${NC}"
                        fi
                    elif echo "$line" | grep -qE ">.*FAILED|FAILED"; then
                        local test_name=$(echo "$line" | sed -n 's/.*> \([^ ]*\).*/\1/p')
                        if [ -z "$test_name" ]; then
                            test_name=$(echo "$line" | grep -oE "[a-zA-Z0-9_]+Test\.\w+" | head -1)
                        fi
                        if [ -n "$test_name" ]; then
                            echo -e "${RED}  ✗ ${test_name}${NC}"
                        fi
                    fi
                done
                last_line=$total_lines
            fi
        fi
        sleep 0.5
    done
}

# Use tee to show output on console AND save to log
echo -e "${BLUE}Starting tests...${NC}"
echo ""

# Start progress monitor in background
show_progress /tmp/test_results.log &
PROGRESS_PID=$!

# Kill progress monitor on exit
trap "kill $PROGRESS_PID 2>/dev/null || true" EXIT

if timeout $TEST_TIMEOUT \
    bash -c "./gradlew $GRADLE_CMD --info 2>&1 | tee /tmp/test_results.log"; then
    TEST_SUCCESS=true
fi

# Kill progress monitor
kill $PROGRESS_PID 2>/dev/null || true
trap - EXIT

TEST_END=$(date +%s)
TEST_DURATION=$((TEST_END - TEST_START))

# Parse test results from log
PASSED=0
FAILED=0
if [ -f /tmp/test_results.log ]; then
    PASSED=$(grep "PASSED" /tmp/test_results.log 2>/dev/null | wc -l | tr -d ' ')
    FAILED=$(grep "FAILED" /tmp/test_results.log 2>/dev/null | wc -l | tr -d ' ')
fi

# Show results
echo ""
echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  Test Summary${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

if [ "$FAILED" -eq 0 ] && [ "$PASSED" -gt 0 ]; then
    echo -e "${GREEN}  ✓ All tests passed${NC}"
elif [ "$FAILED" -gt 0 ]; then
    echo -e "${RED}  ✗ Some tests failed${NC}"
else
    echo -e "${YELLOW}  ⚠ No test results found${NC}"
fi

echo ""
echo -e "  ${GREEN}Passed:  $PASSED${NC}"
if [ "$FAILED" -gt 0 ]; then
    echo -e "  ${RED}Failed:  $FAILED${NC}"
fi
echo ""
echo -e "  ${BLUE}Duration: ${TEST_DURATION}s${NC}"
echo -e "${BLUE}============================================${NC}"

# Show failed test names if any
if [ "$FAILED" -gt 0 ] && [ -f /tmp/test_results.log ]; then
    echo ""
    echo -e "${RED}Failed tests:${NC}"
    grep "FAILED" /tmp/test_results.log | sed 's/^/  /' | head -20
fi

# Return appropriate exit code
if [ "$TEST_SUCCESS" = true ]; then
    exit 0
else
    exit 1
fi
