#!/bin/bash
#
# Screenshot sweep for localization testing
# Usage: ./scripts/screenshot-sweep.sh [EMULATOR_SERIAL] [OUTPUT_DIR]
#

set -e

# Configuration
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/.Android/Sdk}"
ADB_BIN="$SDK_ROOT/platform-tools/adb"
if [ ! -f "$ADB_BIN" ]; then
    ADB_BIN="adb"
fi

# Detect running emulators
EMULATORS=$($ADB_BIN devices | grep "emulator-" | grep "device$" | cut -f1)
if [ -z "$EMULATORS" ]; then
    echo "Error: No running emulators detected."
    exit 1
fi

TARGET_EMU=${1:-$(echo "$EMULATORS" | head -n1)}
OUTPUT_DIR=${2:-"screenshots"}

echo "Targeting emulator: $TARGET_EMU"
echo "Output directory: $OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

# Wake up the device and unlock
$ADB_BIN -s "$TARGET_EMU" shell input keyevent KEYCODE_WAKEUP
$ADB_BIN -s "$TARGET_EMU" shell input keyevent 82 || true
# Go to home screen to show widgets
$ADB_BIN -s "$TARGET_EMU" shell input keyevent 3
sleep 1

# Save original locale to restore later
ORIGINAL_LOCALE=$($ADB_BIN -s "$TARGET_EMU" shell cmd locale get-app-locales com.weatherwidget --user 0 2>/dev/null | tr -d '\r\n')
echo "Original app locale: '$ORIGINAL_LOCALE'"

locales=("en-XA" "ar-XB" "de" "bn" "th")

for loc in "${locales[@]}"; do
    echo "--------------------------------------------------"
    echo "Setting app locale to: $loc"
    $ADB_BIN -s "$TARGET_EMU" shell cmd locale set-app-locales com.weatherwidget --user 0 --locales "$loc"
    
    echo "Triggering widget refresh..."
    $ADB_BIN -s "$TARGET_EMU" shell am broadcast -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget
    
    echo "Waiting for render..."
    sleep 3
    
    # Capture screen
    PNG_FILE="/tmp/screenshot_${loc}.png"
    JPG_FILE="$OUTPUT_DIR/screenshot_${loc}.jpg"
    
    echo "Capturing screen to $JPG_FILE..."
    $ADB_BIN -s "$TARGET_EMU" exec-out screencap -p > "$PNG_FILE"
    
    # Convert to JPG (per CLAUDE.md caveat to strip potential adb warnings or corrupt bytes)
    convert "$PNG_FILE" "$JPG_FILE"
    rm -f "$PNG_FILE"
    
    echo "Saved: $JPG_FILE"
done

# Restore original locale
echo "--------------------------------------------------"
echo "Restoring original app locale: '$ORIGINAL_LOCALE'"
$ADB_BIN -s "$TARGET_EMU" shell cmd locale set-app-locales com.weatherwidget --user 0 --locales "$ORIGINAL_LOCALE"
$ADB_BIN -s "$TARGET_EMU" shell am broadcast -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget

echo "Screenshot sweep complete!"
