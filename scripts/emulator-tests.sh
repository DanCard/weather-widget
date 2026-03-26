#!/bin/bash
#
# Run instrumented tests on an Android emulator
# Automatically launches emulator, runs tests, and shuts down
#
# Usage:
#   ./scripts/emulator-tests.sh                    # Use default emulator (visible GUI)
#   ./scripts/emulator-tests.sh -q                 # Run headless (quiet/no window)
#   ./scripts/emulator-tests.sh -v                 # Verbose output
#   ./scripts/emulator-tests.sh -e EMULATOR_NAME   # Use specific emulator
#   ./scripts/emulator-tests.sh -c CLASS_NAME      # Run specific test class
#   ./scripts/emulator-tests.sh -d DURATION        # Set test timeout (e.g. 10m, 450s, 1h)
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
VERBOSE_MODE=false  # Verbose output (default: condensed)
PROGRESS_PID=""

LOG_DIR="logs/emulator-tests"
mkdir -p "$LOG_DIR"
DEBUG_LOG="$LOG_DIR/run-emulator-tests-debug-$(date +%Y%m%d-%H%M%S).log"
TEST_RESULTS_LOG="$LOG_DIR/test_results-$$-$(date +%Y%m%d-%H%M%S).log"
ORIGINAL_ARGS=("$@")

# Prune old files (>14 days)
find logs/ -mindepth 1 -mtime +14 -delete 2>/dev/null

debug_log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$DEBUG_LOG"
}

parse_duration_seconds() {
    local value=$1
    local number=""
    local unit=""

    if [[ "$value" =~ ^([0-9]+)([smh]?)$ ]]; then
        number="${BASH_REMATCH[1]}"
        unit="${BASH_REMATCH[2]}"
    else
        return 1
    fi

    case "$unit" in
        ""|"s") echo "$number" ;;
        "m") echo $((number * 60)) ;;
        "h") echo $((number * 3600)) ;;
        *) return 1 ;;
    esac
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
TEST_TIMEOUT_ARG=""

LEAVE_APKS_INSTALLED=true  # Preserve app/test APKs to avoid widget removal side-effects

while getopts "e:c:d:qvuh" opt; do
    case $opt in
        e) EMULATOR_NAME="$OPTARG"; EMULATOR_NAME_EXPLICIT=true ;;
        c) TEST_CLASS="$OPTARG" ;;
        d) TEST_TIMEOUT_ARG="$OPTARG" ;;
        q) VISIBLE_MODE=false ;;
        v) VERBOSE_MODE=true ;;
        u) LEAVE_APKS_INSTALLED=false ;;
        h) SHOW_HELP=true ;;
        *) SHOW_HELP=true ;;
    esac
done

if [ -n "$TEST_TIMEOUT_ARG" ]; then
    if ! TEST_TIMEOUT=$(parse_duration_seconds "$TEST_TIMEOUT_ARG"); then
        echo -e "${RED}Error: Invalid duration '$TEST_TIMEOUT_ARG'${NC}"
        echo "Use an integer number of seconds, or add s/m/h (examples: 300, 90s, 10m, 1h)"
        exit 1
    fi
fi

# Show help
if [ "$SHOW_HELP" = true ]; then
    echo "Usage: $(basename "$0") [OPTIONS]"
    echo ""
    echo "Run instrumented tests on an Android emulator"
    echo ""
    echo "Options:"
    echo "  -q             Run headless (no GUI window, default: visible)"
    echo "  -v             Verbose output (default: condensed)"
    echo "  -u             Allow APK uninstall after tests (default: preserve installed APKs)"
    echo "  -e EMULATOR    Use specific emulator (default: $DEFAULT_EMULATOR)"
    echo "  -c CLASS       Run specific test class (e.g., com.weatherwidget.WidgetSizeCalculatorTest)"
    echo "  -d DURATION    Test timeout (default: ${TEST_TIMEOUT}s; accepts 300, 90s, 10m, 1h)"
    echo "  -h             Show this help"
    echo ""
    echo "Examples:"
    echo "  $(basename "$0")                              # Run tests, keep emulator running"
    echo "  $(basename "$0") -q                           # Headless mode (no GUI window)"
    echo "  $(basename "$0") -e Medium_Phone_API_36      # Use phone emulator"
    echo "  $(basename "$0") -c WidgetSizeCalculatorTest # Run specific test class"
    echo "  $(basename "$0") -d 15m                       # Allow up to 15 minutes for tests"
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

if [ "$VERBOSE_MODE" = true ]; then
    echo -en "${BLUE}$SDK_ROOT${NC} "
fi
debug_log "script start: pid=$$ args='$*' sdk_root=$SDK_ROOT"

# List available emulators if none specified
if [ -z "$EMULATOR_NAME" ]; then
    if [ "$VERBOSE_MODE" = true ]; then
        echo -en "${YELLOW}Available emulators:${NC} "
        $EMU_BIN -list-avds | while read -r avd; do
            if [ "$avd" = "$DEFAULT_EMULATOR" ]; then
                echo -n " * $avd (default) "
            else
                echo -n "   $avd "
            fi
        done
    fi
    
    EMULATOR_NAME="$DEFAULT_EMULATOR"
    
    # Check if default exists
    if ! $EMU_BIN -list-avds | grep -q "^${EMULATOR_NAME}$"; then
        echo -e "${RED}Error: Default emulator '$EMULATOR_NAME' not found${NC}"
        echo "Use -e flag to specify an emulator from the list above"
        exit 1
    fi
fi

echo -en "${BLUE}Selected: $EMULATOR_NAME${NC}  "

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
    if [ -n "${EMULATOR_SERIAL:-}" ] && [ "$VERBOSE_MODE" = true ]; then
        echo -e "${YELLOW}Keeping emulator running${NC}"
        echo -e "${GREEN}Emulator serial: $EMULATOR_SERIAL${NC}"
    fi
    debug_log "cleanup end"
}

# Set trap to ensure cleanup on exit
trap cleanup EXIT INT TERM

# Check if emulator is already running (ignore physical devices like Samsung)
if [ "$VERBOSE_MODE" = true ]; then
    echo -en "${BLUE}Existing emulators:${NC} "
fi
# Filter for emulator-* only, ignore physical devices
EXISTING_EMU=$($ADB_BIN devices | grep "emulator-" | grep "device$" | cut -f1 | head -1)

# Show all connected devices for info
if [ "$VERBOSE_MODE" = true ]; then
    $ADB_BIN devices -l | grep -v "List of devices" | awk '$2 == "device"' | while read -r line; do
        device=$(echo "$line" | awk '{print $1}')
        model=$(echo "$line" | grep -o "model:[^ ]*" | cut -d: -f2)
        if echo "$device" | grep -q "^emulator-"; then
            echo -e "  ${GREEN}✓ $device ($model) - emulator${NC}"
        else
            echo -e "  ${YELLOW}✗ $device ($model) - physical device (ignored)${NC}"
        fi
    done
fi

if [ -n "$EXISTING_EMU" ]; then
    if [ "$VERBOSE_MODE" = true ]; then
        echo -en "${YELLOW}Active: $EXISTING_EMU${NC}  "
    fi
    USE_EXISTING=true
    EMULATOR_SERIAL="$EXISTING_EMU"
else
    USE_EXISTING=false
fi

# Start emulator if needed
if [ "$USE_EXISTING" = false ]; then
    echo -e "${BLUE}Starting emulator...${NC}  This may take 30-60 seconds..."
    
    # Build emulator launch options
    EMU_FLAGS="-gpu swiftshader_indirect -no-boot-anim"
    
    if [ "$VISIBLE_MODE" = false ]; then
        EMU_FLAGS="$EMU_FLAGS -no-window -no-audio"
        if [ "$VERBOSE_MODE" = true ]; then
            echo "Mode: Headless (use -q flag)"
        fi
    else
        if [ "$VERBOSE_MODE" = true ]; then
            echo "Mode: Visible window"
            echo "Note: Make sure you have a display (X11) available"
        fi
    fi
    
    # Launch emulator in background
    # shellcheck disable=SC2086
    nohup "$EMU_BIN" -avd "$EMULATOR_NAME" \
        $EMU_FLAGS \
        > /tmp/emulator_${EMULATOR_NAME}.log 2>&1 &
    
    EMU_PID=$!
    if [ "$VERBOSE_MODE" = true ]; then
        echo "Emulator PID: $EMU_PID"
    fi
    
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
    if [ "$VERBOSE_MODE" = true ]; then
        echo "Waiting for system services..."
    fi
    sleep 10
fi

# Ensure emulator window is not minimized (emulator pauses when minimized)
# Must run before any adb shell commands that need a responsive VM.
if [ "$VISIBLE_MODE" = true ] && command -v xdotool &>/dev/null; then
    EMU_WIN_ID=$(xdotool search --name "$EMULATOR_NAME" 2>/dev/null | tail -1)
    if [ -n "$EMU_WIN_ID" ]; then
        xdotool windowactivate "$EMU_WIN_ID" 2>/dev/null || true
        debug_log "restored emulator window $EMU_WIN_ID (pre-device-info)"
        if [ "$VERBOSE_MODE" = true ]; then
            echo -e "${GREEN}Restored emulator window${NC}"
        fi
    fi
fi

# Show device info
if [ "$VERBOSE_MODE" = true ]; then
    MODEL=$($TIMEOUT_CMD $ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
    echo -en "${BLUE}Device info: product model:${NC} ${MODEL:-Unknown}   Android build version: "
    $TIMEOUT_CMD $ADB_BIN -s "$EMULATOR_SERIAL" shell getprop ro.build.version.release 2>/dev/null || echo "  Unknown"
fi


# Run tests

cd "$PROJECT_DIR"

# If -c specifies a Robolectric/unit test class (in src/test/ but not src/androidTest/),
# redirect to testDebugUnitTest instead of failing on the instrumented runner.
_is_unit_test_class() {
    local class="$1"
    local simple="${class##*.}"
    if find "$PROJECT_DIR/app/src/test" -name "${simple}.kt" 2>/dev/null | grep -q .; then
        if ! find "$PROJECT_DIR/app/src/androidTest" -name "${simple}.kt" 2>/dev/null | grep -q .; then
            return 0
        fi
    fi
    return 1
}

if [ -n "${TEST_CLASS:-}" ] && _is_unit_test_class "$TEST_CLASS"; then
    echo -e "${YELLOW}$TEST_CLASS is a unit test (Robolectric) — running via testDebugUnitTest${NC}"
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    if ! "$PROJECT_DIR/gradlew" testDebugUnitTest --tests "$TEST_CLASS" --console=plain > "$TEST_RESULTS_LOG" 2>&1; then
        echo -e "${RED}Unit test build/run failed${NC}"
        cat "$TEST_RESULTS_LOG"
        exit 1
    fi
    exit 0
fi

# Multi-emulator mode: when multiple emulators are connected, build APKs once then run in parallel.
if [ -z "${EMULATOR_TESTS_TARGET_SERIAL:-}" ] && [ "$EMULATOR_NAME_EXPLICIT" = false ]; then
    mapfile -t CONNECTED_EMULATORS < <($ADB_BIN devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}' | sort -V)
    if [ "${#CONNECTED_EMULATORS[@]}" -gt 1 ]; then
        echo -e "${BLUE}Detected ${#CONNECTED_EMULATORS[@]} connected emulators: ${CONNECTED_EMULATORS[*]}${NC}"
        echo -e "${YELLOW}Building APKs once, then running tests in parallel...${NC}"

        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
        if ! "$PROJECT_DIR/gradlew" assembleDebug assembleDebugAndroidTest --console=plain > "$TEST_RESULTS_LOG" 2>&1; then
            if grep -q "transformDebugClassesWithAsm" "$TEST_RESULTS_LOG" 2>/dev/null; then
                echo -e "${YELLOW}ASM instrumentation error — retrying with clean build...${NC}"
                if ! "$PROJECT_DIR/gradlew" clean assembleDebug assembleDebugAndroidTest --console=plain > "$TEST_RESULTS_LOG" 2>&1; then
                    echo -e "${RED}Build failed${NC}"
                    cat "$TEST_RESULTS_LOG"
                    exit 1
                fi
            else
                echo -e "${RED}Build failed${NC}"
                cat "$TEST_RESULTS_LOG"
                exit 1
            fi
        fi

        APP_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        TEST_APK="$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        if [ ! -f "$APP_APK" ] || [ ! -f "$TEST_APK" ]; then
            echo -e "${RED}APKs not found after build: $APP_APK / $TEST_APK${NC}"
            exit 1
        fi

        _emu_prefix_output() {
            local label="$1" color="$2"
            while IFS= read -r line; do
                printf "%b[%s]%b %s\n" "$color" "$label" "$NC" "$line"
            done
        }

        _run_on_emulator() {
            local serial="$1"
            local emu_log="$2"

            if [ "$VERBOSE_MODE" = true ]; then echo "Pre-test cleanup on $serial"; fi
            $ADB_BIN -s "$serial" shell am force-stop com.weatherwidget          >/dev/null 2>&1 || true
            $ADB_BIN -s "$serial" shell am force-stop com.weatherwidget.test     >/dev/null 2>&1 || true
            $ADB_BIN -s "$serial" shell cmd jobscheduler cancel com.weatherwidget >/dev/null 2>&1 || true

            if [ "$VERBOSE_MODE" = true ]; then echo "Installing APKs on $serial"; fi
            $ADB_BIN -s "$serial" install -r "$APP_APK"  >/dev/null
            $ADB_BIN -s "$serial" install -r "$TEST_APK" >/dev/null

            local instrument_args="-w"
            [ -n "${TEST_CLASS:-}" ] && instrument_args="$instrument_args -e class $TEST_CLASS"

            echo "Running tests on $serial (log: $emu_log)"
            local run_start=$(date +%s)
            # shellcheck disable=SC2086
            $ADB_BIN -s "$serial" shell am instrument $instrument_args \
                com.weatherwidget.test/com.weatherwidget.WeatherWidgetTestRunner \
                > "$emu_log" 2>&1
            local run_end=$(date +%s)
            local run_duration=$((run_end - run_start))

            $ADB_BIN -s "$serial" shell am broadcast \
                -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget >/dev/null 2>&1 || true

            # Parse summary from am instrument output (format: "OK (158 tests)" or "Tests run: N, Failures: N")
            local total failed errors
            if grep -qE "^OK \([0-9]+ tests\)" "$emu_log" 2>/dev/null; then
                total=$(grep -oE "^OK \([0-9]+" "$emu_log" 2>/dev/null | tail -1 | sed 's/^OK (//')
                failed=0
                errors=0
            else
                total=$(grep -E "^Tests run:" "$emu_log" 2>/dev/null | tail -1 | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')
                failed=$(grep -E "^Tests run:" "$emu_log" 2>/dev/null | tail -1 | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')
                errors=$(grep -E "^Tests run:" "$emu_log" 2>/dev/null | tail -1 | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')
            fi
            total=${total:-0}
            failed=${failed:-0}
            errors=${errors:-0}
            local passed=$((total - failed - errors))
            [ "$passed" -lt 0 ] && passed=0

            local last_code
            last_code=$(grep "INSTRUMENTATION_CODE:" "$emu_log" 2>/dev/null | tail -1 | awk '{print $2}')
            if grep -q "FAILURES!!!" "$emu_log" 2>/dev/null; then
                echo "FAILED — Total: $total  Passed: $passed  Failed: $failed  Duration: ${run_duration}s"
                grep -E "FAILED|FAILURES!!!" "$emu_log" 2>/dev/null | head -20
                return 1
            elif [ "$last_code" = "-1" ] || grep -qE "^OK \(" "$emu_log" 2>/dev/null; then
                echo "PASSED — Total: $total  Passed: $passed  Duration: ${run_duration}s"
                return 0
            else
                echo "FAILED — Total: $total  Passed: $passed  Failed: $failed  Duration: ${run_duration}s"
                grep -E "FAILED|FAILURES!!!" "$emu_log" 2>/dev/null | head -20
                return 1
            fi
        }

        PIDS=()
        PROGRESS_PIDS=()
        EMU_COLORS=("$YELLOW" "$BLUE" "$GREEN")
        export VERBOSE_MODE

        _emu_progress_monitor() {
            local serial="$1" emu_log="$2" color="$3"
            local last_count=0 last_print=$SECONDS
            # Wait for log file to appear (APK install phase)
            local waited=0
            while [ ! -f "$emu_log" ] && [ $waited -lt 120 ]; do sleep 1; waited=$((waited + 1)); done
            [ ! -f "$emu_log" ] && return
            while true; do
                local dots
                dots=$(grep -oE '\.' "$emu_log" 2>/dev/null | wc -l)
                if [ "$dots" -gt "$last_count" ] && [ $((SECONDS - last_print)) -ge 7 ]; then
                    printf "%b[%s]%b   ... %d passed\n" "$color" "$serial" "$NC" "$dots"
                    last_count=$dots
                    last_print=$SECONDS
                fi
                if grep -qE "^(OK |Tests run:|FAILURES)" "$emu_log" 2>/dev/null; then break; fi
                sleep 1
            done
        }

        for i in "${!CONNECTED_EMULATORS[@]}"; do
            serial="${CONNECTED_EMULATORS[$i]}"
            color="${EMU_COLORS[$((i % ${#EMU_COLORS[@]}))]}"
            emu_log="$LOG_DIR/test_results_${serial}-$(date +%Y%m%d-%H%M%S).log"
            echo -e "${color}=== Starting on ${serial} ===${NC}"

            _emu_progress_monitor "$serial" "$emu_log" "$color" &
            PROGRESS_PIDS+=($!)

            # Stagger starts to reduce parallel initialization contention (bg anr risk)
            [ "$i" -gt 0 ] && sleep 2

            _run_on_emulator "$serial" "$emu_log" \
                > >(_emu_prefix_output "$serial" "$color") \
                2> >(_emu_prefix_output "$serial" "$color" >&2) &
            PIDS+=($!)
        done

        OVERALL_STATUS=0
        for pid in "${PIDS[@]}"; do
            wait "$pid" || OVERALL_STATUS=1
        done

        # Stop progress monitors
        for pid in "${PROGRESS_PIDS[@]}"; do
            kill "$pid" 2>/dev/null || true
        done

        if [ $OVERALL_STATUS -eq 0 ]; then
            echo -e "${GREEN}All emulators passed${NC}"
        else
            echo -e "${RED}One or more emulators failed — check logs in $LOG_DIR${NC}"
        fi
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
        echo -n "Connected devices: "
        $ADB_BIN devices
        exit 1
    fi
    echo -en "${GREEN}Targeting (override): $EMULATOR_SERIAL${NC} "
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
elif [ -n "$EMULATOR_SERIAL" ]; then
    # We already have an emulator from earlier (started by this script or existing)
    echo -en "${GREEN} $EMULATOR_SERIAL${NC}  "
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
elif [ -n "$EMULATOR_DEVICES" ]; then
    # Use first emulator found
    EMULATOR_SERIAL="$EMULATOR_DEVICES"
    echo -en "${GREEN}Targeting: $EMULATOR_SERIAL${NC}  "
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
if [ "$VERBOSE_MODE" = true ]; then
    DEVICE_MODEL=$($TIMEOUT_CMD $ADB_BIN -s "$ANDROID_SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')
    DEVICE_BRAND=$($TIMEOUT_CMD $ADB_BIN -s "$ANDROID_SERIAL" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')
    echo -en "${BLUE}Target device: $DEVICE_MODEL $DEVICE_BRAND${NC} \t"
fi

# Pre-test cleanup to avoid stale app-side workers/jobs mutating live widget state
# while instrumentation initializes.
if [ "$VERBOSE_MODE" = true ]; then
    echo -e "${YELLOW}Pre-test cleanup: stopping app + canceling scheduled jobs${NC}"
fi
$ADB_BIN -s "$ANDROID_SERIAL" shell am force-stop com.weatherwidget >/dev/null 2>&1 || true
$ADB_BIN -s "$ANDROID_SERIAL" shell am force-stop com.weatherwidget.test >/dev/null 2>&1 || true
$ADB_BIN -s "$ANDROID_SERIAL" shell cmd jobscheduler stop com.weatherwidget >/dev/null 2>&1 || true
$ADB_BIN -s "$ANDROID_SERIAL" shell cmd jobscheduler cancel com.weatherwidget >/dev/null 2>&1 || true
$ADB_BIN -s "$ANDROID_SERIAL" shell cmd jobscheduler stop com.weatherwidget.test >/dev/null 2>&1 || true
$ADB_BIN -s "$ANDROID_SERIAL" shell cmd jobscheduler cancel com.weatherwidget.test >/dev/null 2>&1 || true

# Build gradle command - use ANDROID_SERIAL to target specific device
GRADLE_CMD=":app:connectedDebugAndroidTest"
if [ -n "$TEST_CLASS" ]; then
    GRADLE_CMD="$GRADLE_CMD -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS"
    echo -e "${YELLOW}Running test class: $TEST_CLASS${NC}"
else
    if [ "$VERBOSE_MODE" = true ]; then
        echo -en "${YELLOW}Running all instrumented tests${NC} \t"
    fi
fi
if [ "$VERBOSE_MODE" = true ]; then
    echo -e "${YELLOW}APK install step: connectedDebugAndroidTest builds and installs the application APK and the instrumentation test APK on $ANDROID_SERIAL (Gradle may skip unchanged tasks)${NC}"
    echo -e "${YELLOW}Test timeout: ${TEST_TIMEOUT}s${NC}"
fi

# Run tests with timeout - show output in real-time
TEST_START=$(date +%s)
TEST_SUCCESS=false

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Keep tested/test APKs installed unless explicitly opted out.
# This helps preserve widget state on launchers that react poorly to package uninstall/reinstall cycles.
GRADLE_APK_PRESERVE_ARG=""
if [ "$LEAVE_APKS_INSTALLED" = true ]; then
    GRADLE_APK_PRESERVE_ARG="-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
    if [ "$VERBOSE_MODE" = true ]; then
        echo -e "${YELLOW}APK preservation: enabled (default)${NC}"
    fi
else
    echo -e "${YELLOW}APK preservation: disabled (-u), Gradle may uninstall APKs after run${NC}"
fi

# Function to show test names as they complete
show_progress() {
    local logfile=$1
    local last_line=0
    local pass_count=0
    local last_printed_count=0
    local last_progress_time=$SECONDS

    while true; do
        if [ -f "$logfile" ]; then
            local total_lines
            total_lines=$(wc -l < "$logfile")
            if [ "$total_lines" -gt "$last_line" ]; then
                local new_lines
                new_lines=$(sed -n "$((last_line + 1)),${total_lines}p" "$logfile" | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r')
                while IFS= read -r line; do
                    # Real-time format: "Class > Method[device] SUCCESS" or "Class > Method[device] FAILED"
                    if echo "$line" | grep -qE " > .* FAILED"; then
                        local test_name
                        test_name=$(echo "$line" | sed 's/\[.*//; s/^[[:space:]]*//')
                        echo -e "${RED}  ✗ ${test_name}${NC}"
                    elif echo "$line" | grep -qE " > .* SUCCESS"; then
                        pass_count=$((pass_count + 1))
                    # Batch summary format (only show failures, pass count already tracked via SUCCESS)
                    elif echo "$line" | grep -qE "Execute .*: FAILED"; then
                        local test_name
                        test_name=$(echo "$line" | sed 's/.*Execute //; s/: FAILED.*//')
                        echo -e "${RED}  ✗ ${test_name}${NC}"
                    fi
                done <<< "$new_lines"
                last_line=$total_lines

                # Print progress every ~7 seconds if tests are passing
                if [ $pass_count -gt $last_printed_count ] && [ $((SECONDS - last_progress_time)) -ge 7 ]; then
                    echo -e "${GREEN}  ... ${pass_count} passed${NC}"
                    last_progress_time=$SECONDS
                    last_printed_count=$pass_count
                fi
            fi
        fi
        sleep 0.5
    done
}

if [ "$VERBOSE_MODE" = true ]; then
    echo -e "${BLUE}Starting tests (build/install + execute)...${NC}"
fi
INSTALL_START_TS=$(date +%s)
INSTALL_END_LOGGED=false
echo -en "${YELLOW}APK install started on $ANDROID_SERIAL${NC} \t"
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
    if [ $WAIT_ELAPSED -ge $TEST_TIMEOUT ]; then
        echo -e "${RED}Test timeout after ${TEST_TIMEOUT}s${NC}"
        debug_log "timeout after ${TEST_TIMEOUT}s"
        break
    fi
    if grep -q "BUILD SUCCESSFUL\|BUILD FAILED" "$TEST_RESULTS_LOG" 2>/dev/null; then
        debug_log "detected build completion in output"
        sleep 2  # Brief grace period for final output flush
        break
    fi
    # Detect APK install completion by checking if the test package is on the device
    if [ "$INSTALL_END_LOGGED" = false ] && \
       $ADB_BIN -s "$ANDROID_SERIAL" shell pm path com.weatherwidget.test 2>/dev/null | grep -q "^package:"; then
        INSTALL_END_TS=$(date +%s)
        INSTALL_ELAPSED=$((INSTALL_END_TS - INSTALL_START_TS))
        echo -en "${YELLOW}APK install finished in ${INSTALL_ELAPSED}s${NC}  "
        echo -e "${BLUE}Running tests...${NC}"
        debug_log "apk_install_end: elapsed=${INSTALL_ELAPSED}s trigger=pm_path_detected"
        INSTALL_END_LOGGED=true
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

# Fallback: if the adb poll never detected the install, log it now
if [ "$INSTALL_END_LOGGED" = false ]; then
    INSTALL_END_TS=$(date +%s)
    INSTALL_ELAPSED=$((INSTALL_END_TS - INSTALL_START_TS))
    echo -e "${YELLOW}APK install finished in ${INSTALL_ELAPSED}s${NC}"
    debug_log "apk_install_end: elapsed=${INSTALL_ELAPSED}s trigger=fallback"
fi

# Determine success from build output (exit code unreliable since we may have killed the process)
if grep -q "BUILD SUCCESSFUL" "$TEST_RESULTS_LOG" 2>/dev/null; then
    TEST_SUCCESS=true
fi

# Retry with clean build on transient ASM instrumentation failure (stale incremental cache)
if [ "$TEST_SUCCESS" = false ] && grep -q "transformDebugClassesWithAsm" "$TEST_RESULTS_LOG" 2>/dev/null; then
    echo -e "${YELLOW}ASM instrumentation error — retrying with clean build...${NC}"
    : > "$TEST_RESULTS_LOG"
    # shellcheck disable=SC2086
    script -qfc "./gradlew clean $GRADLE_CMD $GRADLE_APK_PRESERVE_ARG --console=plain --info" \
        "$TEST_RESULTS_LOG" > /dev/null 2>&1 &
    GRADLE_PID=$!
    debug_log "ASM retry: gradle started via script(1) pid=$GRADLE_PID"
    WAIT_ELAPSED=0
    INSTALL_END_LOGGED=false
    while kill -0 $GRADLE_PID 2>/dev/null; do
        if [ $WAIT_ELAPSED -ge $TEST_TIMEOUT ]; then
            echo -e "${RED}Test timeout after ${TEST_TIMEOUT}s${NC}"
            debug_log "ASM retry: timeout after ${TEST_TIMEOUT}s"
            break
        fi
        if grep -q "BUILD SUCCESSFUL\|BUILD FAILED" "$TEST_RESULTS_LOG" 2>/dev/null; then
            debug_log "ASM retry: detected build completion in output"
            sleep 2
            break
        fi
        if [ "$INSTALL_END_LOGGED" = false ] && \
           $ADB_BIN -s "$ANDROID_SERIAL" shell pm path com.weatherwidget.test 2>/dev/null | grep -q "^package:"; then
            INSTALL_END_TS=$(date +%s)
            INSTALL_ELAPSED=$((INSTALL_END_TS - INSTALL_START_TS))
            echo -en "${YELLOW}APK install finished in ${INSTALL_ELAPSED}s${NC}  "
            echo -e "${BLUE}Running tests...${NC}"
            debug_log "ASM retry: apk_install_end: elapsed=${INSTALL_ELAPSED}s"
            INSTALL_END_LOGGED=true
        fi
        sleep 1
        WAIT_ELAPSED=$((WAIT_ELAPSED + 1))
    done
    if kill -0 $GRADLE_PID 2>/dev/null; then
        debug_log "ASM retry: killing gradle pid=$GRADLE_PID"
        kill $GRADLE_PID 2>/dev/null || true
        sleep 1
        kill -9 $GRADLE_PID 2>/dev/null || true
    fi
    wait $GRADLE_PID 2>/dev/null || true
    if grep -q "BUILD SUCCESSFUL" "$TEST_RESULTS_LOG" 2>/dev/null; then
        TEST_SUCCESS=true
    fi
    TEST_END=$(date +%s)
    TEST_DURATION=$((TEST_END - TEST_START))
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

# Prefer a fresh XML summary from this run when available, even if Gradle
# returned BUILD FAILED because some tests failed.
# AGP 8.x and below: app/build/outputs/androidTest-results/connected/debug
# AGP 9.x and above: app/build/test-results/connected/debug
RESULTS_DIR_OLD="$PROJECT_DIR/app/build/outputs/androidTest-results/connected/debug"
RESULTS_DIR_NEW="$PROJECT_DIR/app/build/test-results/connected/debug"
LATEST_REPORT_XML=""

find_latest_xml() {
    local dir=$1
    if [ -d "$dir" ]; then
        # Use find to list XMLs, then use stat to find newest, handles spaces
        find "$dir" -maxdepth 1 -name 'TEST-*.xml' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-
    fi
}

LATEST_REPORT_XML=$(find_latest_xml "$RESULTS_DIR_NEW")
if [ -z "$LATEST_REPORT_XML" ]; then
    LATEST_REPORT_XML=$(find_latest_xml "$RESULTS_DIR_OLD")
fi

if [ -n "$LATEST_REPORT_XML" ]; then
    # Try <testsuites> aggregate tag first
    TESTSUITES_LINE=$(grep -m1 '<testsuites ' "$LATEST_REPORT_XML" || true)
    if [ -n "$TESTSUITES_LINE" ]; then
        TOTAL=$(echo "$TESTSUITES_LINE" | sed -n 's/.* tests="\([0-9]\+\)".*/\1/p')
        FAILED=$(echo "$TESTSUITES_LINE" | sed -n 's/.* failures="\([0-9]\+\)".*/\1/p')
        ERRORS=$(echo "$TESTSUITES_LINE" | sed -n 's/.* errors="\([0-9]\+\)".*/\1/p')
        SKIPPED=$(echo "$TESTSUITES_LINE" | sed -n 's/.* skipped="\([0-9]\+\)".*/\1/p')
    else
        # Fallback to the first <testsuite> tag if aggregate is missing
        TESTSUITE_LINE=$(grep -m1 '<testsuite ' "$LATEST_REPORT_XML" || true)
        if [ -n "$TESTSUITE_LINE" ]; then
            TOTAL=$(echo "$TESTSUITE_LINE" | sed -n 's/.* tests="\([0-9]\+\)".*/\1/p')
            FAILED=$(echo "$TESTSUITE_LINE" | sed -n 's/.* failures="\([0-9]\+\)".*/\1/p')
            ERRORS=$(echo "$TESTSUITE_LINE" | sed -n 's/.* errors="\([0-9]\+\)".*/\1/p')
            SKIPPED=$(echo "$TESTSUITE_LINE" | sed -n 's/.* skipped="\([0-9]\+\)".*/\1/p')
        fi
    fi

    if [ -n "$TOTAL" ]; then
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
    FAILED=$(grep -c "INFO: Execute .*: FAILED\|SEVERE: Execute .*: FAILED\| > .* FAILED" "$TEST_RESULTS_LOG" 2>/dev/null) || FAILED=0
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
if [ "$VERBOSE_MODE" = true ]; then
    echo -en "${BLUE}Debug log: $DEBUG_LOG${NC} \t "
fi
if [ "$TEST_SUCCESS" = false ] || [ "${FAILED:-0}" -gt 0 ] || [ "${ERRORS:-0}" -gt 0 ]; then
    echo -e "${RED}Test log:  $TEST_RESULTS_LOG${NC}"
fi
debug_log "summary printed: total=$TOTAL passed=$PASSED failed=$FAILED errors=$ERRORS skipped=$SKIPPED duration=${TEST_DURATION}s test_success=$TEST_SUCCESS"

# Show per-class pass summary only for fully successful runs.
if [ -f "$TEST_RESULTS_LOG" ] && [ "$TEST_SUCCESS" = true ] && [ "$FAILED" -eq 0 ] && [ "$ERRORS" -eq 0 ]; then
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
            echo -en "  ${GREEN}${class_name}${NC} > Passed ${pass_count} \t"
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

# Recover widget state after tests (pre-test cleanup stops app/workers)
if [ "$VERBOSE_MODE" = true ]; then
    echo -e "${BLUE}Triggering widget refresh to recover UI...${NC}"
fi
$ADB_BIN -s "$EMULATOR_SERIAL" shell am broadcast -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget >/dev/null 2>&1 || true

# Return appropriate exit code
if [ "$TEST_SUCCESS" = true ]; then
    exit 0
else
    exit 1
fi
