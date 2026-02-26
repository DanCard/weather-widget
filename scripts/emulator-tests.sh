#!/bin/bash
#
# Run instrumented tests on an Android emulator
# Automatically launches emulator, runs tests, and shuts down
#
# Usage:
#   ./scripts/emulator-tests.sh                    # Use default emulator (visible GUI)
#   ./scripts/emulator-tests.sh -q                 # Run headless (quiet/no window)
#   ./scripts/emulator-tests.sh -e EMULATOR_NAME   # Use specific emulator
#   ./scripts/emulator-tests.sh -c CLASS_NAME      # Run specific test class
#   ./scripts/emulator-tests.sh -u                 # Allow Gradle to uninstall test APKs after run
#   ./scripts/emulator-tests.sh -h                 # Show help
#

set -e  # Exit on error

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEFAULT_EMULATOR="Generic_Foldable_API36"
EMU_TIMEOUT=120  # Seconds to wait for emulator boot
TEST_TIMEOUT=300 # Seconds for tests to complete
VISIBLE_MODE=true  # Run emulator with GUI window (default)
PROGRESS_PID=""
DEBUG_LOG="/tmp/run-emulator-tests-debug-$(date +%Y%m%d-%H%M%S).log"
TEST_RESULTS_LOG="/tmp/test_results-$$-$(date +%Y%m%d-%H%M%S).log"
ORIGINAL_ARGS=("$@")

debug_log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$DEBUG_LOG"
}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
EMULATOR_NAME=""
EMULATOR_NAME_EXPLICIT=false
TEST_CLASS=""
SHOW_HELP=false

LEAVE_APKS_INSTALLED=true  # Preserve app/test APKs to avoid widget removal side-effects

while getopts "e:c:quh" opt; do
    case $opt in
        e) EMULATOR_NAME="$OPTARG"; EMULATOR_NAME_EXPLICIT=true ;;
        c) TEST_CLASS="$OPTARG" ;;
        q) VISIBLE_MODE=false ;;
        u) LEAVE_APKS_INSTALLED=false ;;
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
    echo "  -u             Allow APK uninstall after tests (default: preserve installed APKs)"
    echo "  -e EMULATOR    Use specific emulator (default: $DEFAULT_EMULATOR)"
    echo "  -c CLASS       Run specific test class (e.g., com.weatherwidget.WidgetSizeCalculatorTest)"
    echo "  -h             Show this help"
    echo ""
    echo "Examples:"
    echo "  $(basename "$0")                              # Run tests, keep emulator running"
    echo "  $(basename "$0") -q                           # Headless mode (no GUI window)"
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
TIMEOUT_CMD=""
if command -v timeout &>/dev/null; then
    TIMEOUT_CMD="timeout 5s"
fi

if [ ! -f "$EMU_BIN" ]; then
    echo -e "${RED}Error: Emulator binary not found at $EMU_BIN${NC}"
    exit 1
fi

if [ ! -f "$ADB_BIN" ]; then
    echo -e "${RED}Error: ADB binary not found at $ADB_BIN${NC}"
    exit 1
fi

echo -en "${BLUE}$SDK_ROOT${NC} "
debug_log "script start: pid=$$ args='$*' sdk_root=$SDK_ROOT"

# List available emulators if none specified
if [ -z "$EMULATOR_NAME" ]; then
    echo -en "${YELLOW}Available emulators:${NC} "
    $EMU_BIN -list-avds | while read -r avd; do
        if [ "$avd" = "$DEFAULT_EMULATOR" ]; then
            echo -n " * $avd (default) "
        else
            echo -n "   $avd "
        fi
    done
    
    EMULATOR_NAME="$DEFAULT_EMULATOR"
    
    # Check if default exists
    if ! $EMU_BIN -list-avds | grep -q "^${EMULATOR_NAME}$"; then
        echo -e "${RED}Error: Default emulator '$EMULATOR_NAME' not found${NC}"
        echo "Use -e flag to specify an emulator from the list above"
        exit 1
    fi
fi

echo -e "${BLUE}Selected: $EMULATOR_NAME${NC}"

# Function to cleanup emulator
cleanup() {
    debug_log "cleanup start: USE_EXISTING=${USE_EXISTING:-unset} EMULATOR_SERIAL=${EMULATOR_SERIAL:-unset} PROGRESS_PID=${PROGRESS_PID:-unset}"

    # Stop progress monitor if running
    if [ -n "${PROGRESS_PID:-}" ]; then
        debug_log "cleanup: kill progress monitor pid=$PROGRESS_PID"
        kill "$PROGRESS_PID" 2>/dev/null || true
        wait "$PROGRESS_PID" 2>/dev/null || true
        debug_log "cleanup: progress monitor stop wait complete"
    fi

    # Always keep emulator running (avoids re-launch overhead on next run)
    if [ -n "${EMULATOR_SERIAL:-}" ]; then
        echo -e "${YELLOW}Keeping emulator running${NC}"
        echo -e "${GREEN}Emulator serial: $EMULATOR_SERIAL${NC}"
    fi
    debug_log "cleanup end"
}

# Set trap to ensure cleanup on exit
trap cleanup EXIT INT TERM

# Check if emulator is already running (ignore physical devices like Samsung)
echo -en "${BLUE}Existing emulators:${NC} "
# Filter for emulator-* only, ignore physical devices
EXISTING_EMU=$($ADB_BIN devices | grep "emulator-" | grep "device$" | cut -f1 | head -1)

# Show all connected devices for info
$ADB_BIN devices -l | grep -v "List of devices" | awk '$2 == "device"' | while read -r line; do
    device=$(echo "$line" | awk '{print $1}')
    model=$(echo "$line" | grep -o "model:[^ ]*" | cut -d: -f2)
    if echo "$device" | grep -q "^emulator-"; then
        echo -e "  ${GREEN}✓ $device ($model) - emulator${NC}"
    else
        echo -e "  ${YELLOW}✗ $device ($model) - physical device (ignored)${NC}"
    fi
done

if [ -n "$EXISTING_EMU" ]; then
    echo -en "${YELLOW}Active: $EXISTING_EMU${NC} \t"
    USE_EXISTING=true
    EMULATOR_SERIAL="$EXISTING_EMU"
else
    USE_EXISTING=false
fi

# Start emulator if needed
if [ "$USE_EXISTING" = false ]; then
    echo -e "${BLUE}Starting emulator...${NC} \t This may take 30-60 seconds..."
    
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
    echo -e "${YELLOW}Waiting for device to boot...${NC}"
    
    START_TIME=$(date +%s)
    DEVICE_READY=false
    
    while [ $(($(date +%s) - START_TIME)) -lt $EMU_TIMEOUT ]; do
        EMULATOR_SERIAL=$($ADB_BIN devices | grep "emulator-" | cut -f1 | head -1)
        
        if [ -n "$EMULATOR_SERIAL" ]; then
            # Check if boot is complete
            BOOT_COMPLETED=$($TIMEOUT_CMD $ADB_BIN -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null || echo "")
            
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
        echo -e "${RED}Error: Emulator failed to boot within ${EMU_TIMEOUT} seconds${NC}"
        echo "Check log: tail -f /tmp/emulator_${EMULATOR_NAME}.log"
        exit 1
    fi
    
    echo -en "${GREEN}Emulator ready: $EMULATOR_SERIAL${NC}\t"
    
    # Additional wait for system stability
    echo "Waiting for system services..."
    sleep 10
fi

# Ensure emulator window is not minimized (emulator pauses when minimized)
# Must run before any adb shell commands that need a responsive VM.
if [ "$VISIBLE_MODE" = true ] && command -v xdotool &>/dev/null; then
    EMU_WIN_ID=$(xdotool search --name "$EMULATOR_NAME" 2>/dev/null | tail -1)
    if [ -n "$EMU_WIN_ID" ]; then
        xdotool windowactivate "$EMU_WIN_ID" 2>/dev/null || true
        debug_log "restored emulator window $EMU_WIN_ID (pre-device-info)"
        echo -e "${GREEN}Restored emulator window${NC}"
    fi
fi

# Show device info
MODEL=$($TIMEOUT_CMD $ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
echo -en "${BLUE}Device info: product model:${NC} ${MODEL:-Unknown}   Android build version: "
$TIMEOUT_CMD $ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.build.version.release 2>/dev/null || echo "  Unknown"


# Run tests

cd "$PROJECT_DIR"

# Multi-emulator mode: when multiple emulators are connected, run this script once per emulator.
# This preserves existing single-device behavior while covering all active emulator-* devices.
if [ -z "${EMULATOR_TESTS_TARGET_SERIAL:-}" ] && [ "$EMULATOR_NAME_EXPLICIT" = false ]; then
    mapfile -t CONNECTED_EMULATORS < <($ADB_BIN devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}' | sort -V)
    if [ "${#CONNECTED_EMULATORS[@]}" -gt 1 ]; then
        echo -e "${BLUE}Detected ${#CONNECTED_EMULATORS[@]} connected emulators: ${CONNECTED_EMULATORS[*]}${NC}"
        echo -e "${YELLOW}Running tests sequentially on each emulator...${NC}"
        OVERALL_STATUS=0
        for serial in "${CONNECTED_EMULATORS[@]}"; do
            echo -e "${BLUE}=== Running on ${serial} ===${NC}"
            if ! EMULATOR_TESTS_TARGET_SERIAL="$serial" "$0" "${ORIGINAL_ARGS[@]}"; then
                OVERALL_STATUS=1
            fi
        done
        exit $OVERALL_STATUS
    fi
fi

# Check for multiple devices - filter to emulator only
# echo -en "${BLUE}Checking connected devices...${NC} \t"
ALL_DEVICES=$($ADB_BIN devices | grep -v "List of devices" | grep "device$" | cut -f1)
EMULATOR_DEVICES=$(echo "$ALL_DEVICES" | grep "^emulator-" | head -1)

if [ -n "${EMULATOR_TESTS_TARGET_SERIAL:-}" ]; then
    EMULATOR_SERIAL="$EMULATOR_TESTS_TARGET_SERIAL"
    if ! echo "$ALL_DEVICES" | grep -qx "$EMULATOR_SERIAL"; then
        echo -e "${RED}Error: Requested emulator serial not connected: $EMULATOR_SERIAL${NC}"
        echo "Connected devices:"
        $ADB_BIN devices
        exit 1
    fi
    echo -en "${GREEN}Targeting (override): $EMULATOR_SERIAL${NC} "
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
elif [ -n "$EMULATOR_SERIAL" ]; then
    # We already have an emulator from earlier (started by this script or existing)
    echo -en "${GREEN}Targeting: $EMULATOR_SERIAL${NC} "
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
elif [ -n "$EMULATOR_DEVICES" ]; then
    # Use first emulator found
    EMULATOR_SERIAL="$EMULATOR_DEVICES"
    echo -en "${GREEN}Targeting: $EMULATOR_SERIAL${NC} "
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
else
    echo -e "${RED}Error: No emulator found${NC}"
    echo "Connected devices:"
    $ADB_BIN devices
    echo ""
    echo "Please connect an emulator or start one with this script."
    exit 1
fi

# Verify we're not targeting a physical Samsung device
DEVICE_MODEL=$($TIMEOUT_CMD $ADB_BIN -s "$ANDROID_SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')
DEVICE_BRAND=$($TIMEOUT_CMD $ADB_BIN -s "$ANDROID_SERIAL" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')
echo -en "${BLUE}Target device: $DEVICE_MODEL $DEVICE_BRAND${NC} \t"

# Build gradle command - use ANDROID_SERIAL to target specific device
GRADLE_CMD=":app:connectedDebugAndroidTest"
if [ -n "$TEST_CLASS" ]; then
    GRADLE_CMD="$GRADLE_CMD -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS"
    echo -e "${YELLOW}Running test class: $TEST_CLASS${NC}"
else
    echo -en "${YELLOW}Running all instrumented tests${NC} \t"
fi
echo -e "${YELLOW}APK install step: connectedDebugAndroidTest builds and installs the application APK and the instrumentation test APK on $ANDROID_SERIAL (Gradle may skip unchanged tasks)${NC}"

# Run tests with timeout - show output in real-time
TEST_START=$(date +%s)
TEST_SUCCESS=false

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Keep tested/test APKs installed unless explicitly opted out.
# This helps preserve widget state on launchers that react poorly to package uninstall/reinstall cycles.
GRADLE_APK_PRESERVE_ARG=""
if [ "$LEAVE_APKS_INSTALLED" = true ]; then
    GRADLE_APK_PRESERVE_ARG="-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
    echo -e "${YELLOW}APK preservation: enabled (default)${NC}"
else
    echo -e "${YELLOW}APK preservation: disabled (-u), Gradle may uninstall APKs after run${NC}"
fi

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
                local new_lines
                new_lines=$(sed -n "$((last_line + 1)),${total_lines}p" "$logfile")
                while IFS= read -r line; do
                    # Connected test output format: "INFO: Execute com.package.Class.methodName: PASSED"
                    if echo "$line" | grep -qE "INFO: Execute .*: (PASSED|FAILED|SKIPPED)"; then
                        local status=$(echo "$line" | grep -oE "(PASSED|FAILED|SKIPPED)")
                        local test_full=$(echo "$line" | sed -n "s/.*INFO: Execute \(.*\): .*/\1/p")

                        if [ -n "$test_full" ]; then
                            local method_part="${test_full##*.}"
                            local class_full="${test_full%.*}"
                            local simple_class="${class_full##*.}"

                            case "$status" in
                                PASSED)  : ;; # Summarized per class at end
                                FAILED)  echo -e "${RED}  ✗ ${simple_class} > ${method_part}${NC}" ;;
                                SKIPPED) echo -e "${YELLOW}  - ${simple_class} > ${method_part} (SKIPPED)${NC}" ;;
                            esac
                        fi
                    # Fallback for standard Gradle test output: "Class > Method PASSED"
                    elif echo "$line" | grep -qE " > .* (PASSED|FAILED|SKIPPED)"; then
                        local status=$(echo "$line" | grep -oE "(PASSED|FAILED|SKIPPED)")
                        local test_full=$(echo "$line" | sed -n "s/ \(PASSED\|FAILED\|SKIPPED\).*//p" | sed 's/.*:app:connectedDebugAndroidTest //')

                        if [ -n "$test_full" ]; then
                            local class_part=$(echo "$test_full" | cut -d'>' -f1 | sed 's/ //g')
                            local method_part=$(echo "$test_full" | cut -d'>' -f2 | sed 's/ //g')
                            local simple_class="${class_part##*.}"

                            case "$status" in
                                PASSED)  : ;; # Summarized per class at end
                                FAILED)  echo -e "${RED}  ✗ ${simple_class} > ${method_part}${NC}" ;;
                                SKIPPED) echo -e "${YELLOW}  - ${simple_class} > ${method_part} (SKIPPED)${NC}" ;;
                            esac
                        fi
                    fi
                done <<< "$new_lines"
                last_line=$total_lines
            fi
        fi
        sleep 0.5
    done
}

echo -e "${BLUE}Starting tests (build/install + execute)...${NC}"
INSTALL_START_TS=$(date +%s)
INSTALL_END_LOGGED=false
echo -e "${YELLOW}APK install started on $ANDROID_SERIAL${NC}"
debug_log "apk_install_start: serial=$ANDROID_SERIAL"

# Truncate log so show_progress doesn't see stale content
: > "$TEST_RESULTS_LOG"

# Start progress monitor in background
show_progress "$TEST_RESULTS_LOG" &
PROGRESS_PID=$!
debug_log "progress monitor started pid=$PROGRESS_PID"

# Run gradle in background via script(1) for line-buffered output.
# script creates a pseudo-terminal so Gradle flushes each line immediately,
# letting show_progress display checkmarks in real time.
# shellcheck disable=SC2086
script -qfc "./gradlew $GRADLE_CMD $GRADLE_APK_PRESERVE_ARG --console=plain --info" \
    "$TEST_RESULTS_LOG" > /dev/null 2>&1 &
GRADLE_PID=$!
debug_log "gradle started via script(1) pid=$GRADLE_PID"

# Poll for build completion or timeout.
# Gradle's JVM sometimes hangs after BUILD SUCCESSFUL (daemon threads, ADB cleanup),
# so we detect completion from output rather than waiting for process exit.
WAIT_ELAPSED=0
while kill -0 $GRADLE_PID 2>/dev/null; do
    if [ "$INSTALL_END_LOGGED" = false ] && grep -qE "INFO: Execute .*: (PASSED|FAILED|SKIPPED)| > .* (PASSED|FAILED|SKIPPED)" "$TEST_RESULTS_LOG" 2>/dev/null; then
        INSTALL_END_TS=$(date +%s)
        INSTALL_ELAPSED=$((INSTALL_END_TS - INSTALL_START_TS))
        echo -e "${YELLOW}APK install finished in ${INSTALL_ELAPSED}s${NC}"
        debug_log "apk_install_end: elapsed=${INSTALL_ELAPSED}s trigger=first_test_output"
        INSTALL_END_LOGGED=true
    fi

    if [ $WAIT_ELAPSED -ge $TEST_TIMEOUT ]; then
        echo -e "${RED}Test timeout after ${TEST_TIMEOUT}s${NC}"
        debug_log "timeout after ${TEST_TIMEOUT}s"
        break
    fi
    if grep -q "BUILD SUCCESSFUL\|BUILD FAILED" "$TEST_RESULTS_LOG" 2>/dev/null; then
        if [ "$INSTALL_END_LOGGED" = false ]; then
            INSTALL_END_TS=$(date +%s)
            INSTALL_ELAPSED=$((INSTALL_END_TS - INSTALL_START_TS))
            echo -e "${YELLOW}APK install finished in ${INSTALL_ELAPSED}s${NC}"
            debug_log "apk_install_end: elapsed=${INSTALL_ELAPSED}s trigger=build_complete"
            INSTALL_END_LOGGED=true
        fi
        debug_log "detected build completion in output"
        sleep 2  # Brief grace period for final output flush
        break
    fi
    sleep 1
    WAIT_ELAPSED=$((WAIT_ELAPSED + 1))
done

# Kill gradle if still running (it often hangs after build completes)
if kill -0 $GRADLE_PID 2>/dev/null; then
    debug_log "killing gradle pid=$GRADLE_PID (post-build or timeout)"
    kill $GRADLE_PID 2>/dev/null || true
    sleep 1
    kill -9 $GRADLE_PID 2>/dev/null || true
fi
wait $GRADLE_PID 2>/dev/null || true

# Determine success from build output (exit code unreliable since we may have killed the process)
if grep -q "BUILD SUCCESSFUL" "$TEST_RESULTS_LOG" 2>/dev/null; then
    TEST_SUCCESS=true
fi

# Kill progress monitor
kill $PROGRESS_PID 2>/dev/null || true
wait "$PROGRESS_PID" 2>/dev/null || true
debug_log "progress monitor stop requested after test run"

TEST_END=$(date +%s)
TEST_DURATION=$((TEST_END - TEST_START))

# Parse test results (prefer XML summary from Android test output)
TOTAL=0
PASSED=0
FAILED=0
ERRORS=0
SKIPPED=0

# When the build failed (compile error etc.), tests never ran — stale XML would
# give misleading counts.  Only parse XML when tests actually executed.
RESULTS_DIR="$PROJECT_DIR/app/build/outputs/androidTest-results/connected/debug"
LATEST_REPORT_XML=""
if [ "$TEST_SUCCESS" = true ]; then
    LATEST_REPORT_XML=$(ls -1t "$RESULTS_DIR"/TEST-*.xml 2>/dev/null | head -1 || true)
fi

if [ -n "$LATEST_REPORT_XML" ]; then
    TESTSUITE_LINE=$(grep -m1 '<testsuite ' "$LATEST_REPORT_XML" || true)
    if [ -n "$TESTSUITE_LINE" ]; then
        TOTAL=$(echo "$TESTSUITE_LINE" | sed -n 's/.* tests="\([0-9]\+\)".*/\1/p')
        FAILED=$(echo "$TESTSUITE_LINE" | sed -n 's/.* failures="\([0-9]\+\)".*/\1/p')
        ERRORS=$(echo "$TESTSUITE_LINE" | sed -n 's/.* errors="\([0-9]\+\)".*/\1/p')
        SKIPPED=$(echo "$TESTSUITE_LINE" | sed -n 's/.* skipped="\([0-9]\+\)".*/\1/p')

        TOTAL=${TOTAL:-0}
        FAILED=${FAILED:-0}
        ERRORS=${ERRORS:-0}
        SKIPPED=${SKIPPED:-0}
        PASSED=$((TOTAL - FAILED - ERRORS - SKIPPED))
        if [ "$PASSED" -lt 0 ]; then
            PASSED=0
        fi
    fi
fi

# Fallback to log parsing when XML summary is unavailable
if [ "$TOTAL" -eq 0 ] && [ -f "$TEST_RESULTS_LOG" ]; then
    PASSED=$(grep -c "INFO: Execute .*: PASSED\| > .* PASSED" "$TEST_RESULTS_LOG" 2>/dev/null) || PASSED=0
    FAILED=$(grep -c "INFO: Execute .*: FAILED\| > .* FAILED" "$TEST_RESULTS_LOG" 2>/dev/null) || FAILED=0
    ERRORS=0
    SKIPPED=0
    TOTAL=$((PASSED + FAILED))
fi

# Show results
echo -en "${BLUE}  Test Summary:${NC} \t"
if [ "$TEST_SUCCESS" = false ]; then
    if [ "$TOTAL" -eq 0 ]; then
        echo -e "${RED}  ✗ Build failed (tests did not run)${NC}"
    else
        echo -e "${RED}  ✗ Some tests failed${NC}"
    fi
elif [ "$TOTAL" -gt 0 ] && [ "$FAILED" -eq 0 ] && [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}  ✓ All tests passed${NC}"
else
    echo -e "${YELLOW}  ⚠ No test results found${NC}"
fi

echo -e "  ${BLUE}Total:   $TOTAL${NC}"
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
echo -e "  ${BLUE}Duration: ${TEST_DURATION}s${NC}"
echo -en "${BLUE}Debug log: $DEBUG_LOG${NC} \t "
debug_log "summary printed: total=$TOTAL passed=$PASSED failed=$FAILED errors=$ERRORS skipped=$SKIPPED duration=${TEST_DURATION}s test_success=$TEST_SUCCESS"

# Show per-class pass summary (compact replacement for per-test PASSED spam)
if [ -f "$TEST_RESULTS_LOG" ]; then
    HAS_INFO_EXECUTE=false
    if grep -qE "INFO: Execute .*: (PASSED|FAILED|SKIPPED)" "$TEST_RESULTS_LOG" 2>/dev/null; then
        HAS_INFO_EXECUTE=true
    fi

    if [ "$HAS_INFO_EXECUTE" = true ]; then
        CLASS_PASS_SUMMARY=$(awk '
            /INFO: Execute .*: PASSED/ {
                line=$0
                sub(/^.*INFO: Execute /, "", line)
                sub(/: PASSED.*$/, "", line)
                class=line
                sub(/\.[^.]+$/, "", class)
                simple=class
                sub(/^.*\./, "", simple)
                if (simple != "") counts[simple]++
            }
            END {
                for (k in counts) print k "\t" counts[k]
            }
        ' "$TEST_RESULTS_LOG" | sort)
    else
        CLASS_PASS_SUMMARY=$(awk '
            / > .* PASSED/ {
                line=$0
                sub(/^.*:app:connectedDebugAndroidTest /, "", line)
                split(line, parts, " > ")
                class=parts[1]
                gsub(/[[:space:]]+/, "", class)
                simple=class
                sub(/^.*\./, "", simple)
                if (simple != "") counts[simple]++
            }
            END {
                for (k in counts) print k "\t" counts[k]
            }
        ' "$TEST_RESULTS_LOG" | sort)
    fi

    if [ -n "$CLASS_PASS_SUMMARY" ]; then
        echo -e "${BLUE}Per-class pass summary:${NC}"
        while IFS=$'\t' read -r class_name pass_count; do
            [ -z "$class_name" ] && continue
            echo -e "  ${GREEN}${class_name}${NC} > Passed ${pass_count} tests"
        done <<< "$CLASS_PASS_SUMMARY"
    fi
fi

# Show build errors (compile failures, etc.) when build failed
if [ "$TEST_SUCCESS" = false ] && [ -f "$TEST_RESULTS_LOG" ]; then
    BUILD_FAILURE_HEAD=$(awk '
        BEGIN { in_block=0; count=0 }
        /^FAILURE: Build failed with an exception\./ { in_block=1 }
        in_block {
            print
            count++
            if (count >= 25) exit
        }
    ' "$TEST_RESULTS_LOG")
    if [ -n "$BUILD_FAILURE_HEAD" ]; then
        echo -e "${RED}Build failure details:${NC}"
        echo "$BUILD_FAILURE_HEAD" | while IFS= read -r line; do
            echo -e "  ${RED}$line${NC}"
        done
    fi

    COMPILE_ERRORS=$(grep "^e: " "$TEST_RESULTS_LOG" 2>/dev/null | head -20)
    if [ -n "$COMPILE_ERRORS" ]; then
        echo -e "${RED}Compile errors:${NC}"
        echo "$COMPILE_ERRORS" | while IFS= read -r line; do
            echo -e "  ${RED}$line${NC}"
        done
    fi
fi

# Show failed test names if any
if [ "$FAILED" -gt 0 ] && [ -f "$TEST_RESULTS_LOG" ]; then
    echo -e "${RED}Failed tests:${NC}"
    # Match only real failed test lines, not generic "BUILD FAILED" lines.
    awk '
        /INFO: Execute .*: FAILED/ || /SEVERE: Execute .*: FAILED/ {
            line=$0
            sub(/^.*Execute /, "", line)
            sub(/: FAILED.*$/, "", line)
            if (line != "") print "  ✗ " line
            next
        }
        / > .* FAILED/ {
            line=$0
            sub(/^.*:app:connectedDebugAndroidTest /, "", line)
            sub(/ FAILED.*$/, "", line)
            if (line != "") print "  ✗ " line
        }
    ' "$TEST_RESULTS_LOG" | head -20
fi

# Return appropriate exit code
if [ "$TEST_SUCCESS" = true ]; then
    exit 0
else
    exit 1
fi
