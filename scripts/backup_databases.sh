#!/bin/bash
#
# Robust Weather Widget database backup from ALL Android devices
#
# Features:
# - Dynamic file discovery (no hardcoded .db extensions)
# - Device model identification
# - Record counts and metadata
# - Root/su fallback
# - Shared Preferences backup

PACKAGE_NAME="com.weatherwidget"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_ROOT="backups"

echo "=== Weather Widget Backup Tool ==="

# Get list of connected devices
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | cut -f1)

if [ -z "$DEVICES" ]; then
    echo "Error: No devices found. Please connect a device via USB or Wireless ADB."
    exit 1
fi

mkdir -p "$BACKUP_ROOT"

for DEVICE_ID in $DEVICES; do
    # Get device model for better folder naming
    MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model | tr -d '\r ' | tr '[:upper:]' '[:lower:]')
    SAFE_ID=$(echo "$DEVICE_ID" | tr -d '.:' | tr '/' '_')
    FOLDER_NAME="${TIMESTAMP}_${MODEL}_${SAFE_ID}"
    DEST_DIR="$BACKUP_ROOT/$FOLDER_NAME"
    
    echo "------------------------------------------------"
    echo "Device: $DEVICE_ID ($MODEL)"
    
    # Check if app is installed
    if ! adb -s "$DEVICE_ID" shell pm list packages | grep -q "$PACKAGE_NAME"; then
        echo "  [!] App $PACKAGE_NAME not found. Skipping."
        continue
    fi

    mkdir -p "$DEST_DIR"

    # Helper function to copy files
    copy_files() {
        local subfolder=$1
        local target_subdir=$2
        mkdir -p "$DEST_DIR/$target_subdir"
        
        # Try run-as first
        local files=$(adb -s "$DEVICE_ID" shell "run-as $PACKAGE_NAME ls /data/data/$PACKAGE_NAME/$subfolder/" 2>/dev/null)
        local method="run-as"
        
        # Fallback to su if run-as fails or returns nothing
        if [ -z "$files" ]; then
            files=$(adb -s "$DEVICE_ID" shell "su -c 'ls /data/data/$PACKAGE_NAME/$subfolder/'" 2>/dev/null)
            method="su"
        fi

        for f in $files; do
            f=$(echo "$f" | tr -d '\r\n')
            [ -z "$f" ] && continue
            
            echo "  Copying $f ($method)..."
            if [ "$method" == "run-as" ]; then
                adb -s "$DEVICE_ID" exec-out run-as "$PACKAGE_NAME" cat "/data/data/$PACKAGE_NAME/$subfolder/$f" > "$DEST_DIR/$target_subdir/$f"
            else
                adb -s "$DEVICE_ID" exec-out su -c "cat /data/data/$PACKAGE_NAME/$subfolder/$f" > "$DEST_DIR/$target_subdir/$f"
            fi
        done
    }

    echo "  Backing up databases..."
    copy_files "databases" "databases"
    
    echo "  Backing up shared preferences..."
    copy_files "shared_prefs" "shared_prefs"

    # Generate Stats if sqlite3 is available locally
    DB_PATH="$DEST_DIR/databases/weather_database"
    if [ -f "$DB_PATH" ] && command -v sqlite3 &>/dev/null; then
        echo "  Analyzing database..."
        {
            echo "Database: weather_database"
            echo "Record Counts:"
            sqlite3 "$DB_PATH" "SELECT '  - Weather records: ' || count(*) FROM weather_data;" 2>/dev/null || echo "  - weather_data: Table not found"
            sqlite3 "$DB_PATH" "SELECT '  - Forecast snapshots: ' || count(*) FROM forecast_snapshots;" 2>/dev/null || echo "  - forecast_snapshots: Table not found"
            sqlite3 "$DB_PATH" "SELECT '  - Hourly forecasts: ' || count(*) FROM hourly_forecasts;" 2>/dev/null || echo "  - hourly_forecasts: Table not found"
        } > "$DEST_DIR/stats.txt"
    fi

    # Create metadata
    echo "Backup Time: $(date)" > "$DEST_DIR/metadata.txt"
    echo "Device ID: $DEVICE_ID" >> "$DEST_DIR/metadata.txt"
    echo "Model: $MODEL" >> "$DEST_DIR/metadata.txt"
    
    echo "  Capturing logcat..."
    adb -s "$DEVICE_ID" logcat -d -t 1000 > "$DEST_DIR/logcat.txt" 2>/dev/null

    echo "  Done: $DEST_DIR"
done

echo "------------------------------------------------"
echo "All backups complete in $BACKUP_ROOT/"