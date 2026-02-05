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
# - Verbose logging

PACKAGE_NAME="com.weatherwidget"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_ROOT="backups"

echo "=== Weather Widget Backup Tool ==="
echo "Timestamp: $TIMESTAMP"
echo "Backup Root: $BACKUP_ROOT"

# Get list of connected devices
echo ""
echo "[*] Detecting connected devices..."
DEVICES=$(adb devices | awk '$2 == "device" {print $1}')

if [ -z "$DEVICES" ]; then
    echo "[!] Error: No devices found. Please connect a device via USB or Wireless ADB."
    exit 1
fi

echo "[+] Found $(echo "$DEVICES" | wc -l) device(s):"
for DEVICE_ID in $DEVICES; do
    MODEL=$(timeout 10s adb -s "$DEVICE_ID" shell getprop ro.product.model | tr -d '\r ')
    if [ $? -eq 124 ]; then MODEL="UNRESPONSIVE"; fi
    echo "    - $DEVICE_ID ($MODEL)"
done

echo ""
echo "[*] Ensuring backup directory exists..."
mkdir -p "$BACKUP_ROOT"
echo "[+] Backup directory ready: $BACKUP_ROOT"

echo ""
echo "============================================================"

DEVICE_COUNT=0
for DEVICE_ID in $DEVICES; do
    DEVICE_COUNT=$((DEVICE_COUNT + 1))
    echo "[*] Processing device $DEVICE_COUNT of $(echo "$DEVICES" | wc -l)..."
    
    # Get device model for better folder naming
    MODEL=$(timeout 10s adb -s "$DEVICE_ID" shell getprop ro.product.model | tr -d '\r ' | tr '[:upper:]' '[:lower:]')
    if [ -z "$MODEL" ]; then MODEL="unknown_device"; fi
    
    SAFE_ID=$(echo "$DEVICE_ID" | tr -d '.:' | tr '/' '_')
    FOLDER_NAME="${TIMESTAMP}_${MODEL}_${SAFE_ID}"
    DEST_DIR="$BACKUP_ROOT/$FOLDER_NAME"
    
    echo "------------------------------------------------------------"
    echo "Device: $DEVICE_ID"
    echo "Model: $MODEL"
    echo "Destination: $DEST_DIR"
    echo "------------------------------------------------------------"
    
    # Check if app is installed
    echo "[*] Checking if $PACKAGE_NAME is installed..."
    PKGS=$(timeout 15s adb -s "$DEVICE_ID" shell pm list packages 2>/dev/null)
    EXIT_CODE=$?
    
    if [ $EXIT_CODE -eq 124 ]; then
        echo "[!] Timeout while checking packages on device $DEVICE_ID. Device may be hung. Skipping."
        echo ""
        continue
    fi

    if ! echo "$PKGS" | grep -q "$PACKAGE_NAME"; then
        echo "[!] App $PACKAGE_NAME not found on device $DEVICE_ID. Skipping."
        echo ""
        continue
    fi
    echo "[+] App found on device"

    echo "[*] Creating backup directory structure..."
    mkdir -p "$DEST_DIR"
    echo "[+] Directory created: $DEST_DIR"

    # Helper function to copy files
    copy_files() {
        local subfolder=$1
        local target_subdir=$2
        local file_count=0
        mkdir -p "$DEST_DIR/$target_subdir"
        
        echo ""
        echo "[*] Attempting to access /data/data/$PACKAGE_NAME/$subfolder ..."
        
        # Try run-as first
        local files=$(timeout 20s adb -s "$DEVICE_ID" shell "run-as $PACKAGE_NAME ls /data/data/$PACKAGE_NAME/$subfolder/" 2>/dev/null)
        local method="run-as"
        
        # Fallback to su if run-as fails or returns nothing
        if [ -z "$files" ]; then
            echo "[-] run-as failed or returned no files. Trying su method..."
            files=$(timeout 20s adb -s "$DEVICE_ID" shell "su -c 'ls /data/data/$PACKAGE_NAME/$subfolder/'" 2>/dev/null)
            method="su"
        fi

        if [ -z "$files" ]; then
            echo "[!] No files found in $subfolder using either method"
            return
        fi

        echo "[+] Access method: $method"
        echo "[*] Found $(echo "$files" | wc -w) file(s) to copy..."
        
        for f in $files; do
            f=$(echo "$f" | tr -d '\r\n')
            [ -z "$f" ] && continue
            
            file_count=$((file_count + 1))
            echo "    [$file_count] Copying: $f"
            
            if [ "$method" == "run-as" ]; then
                timeout 30s adb -s "$DEVICE_ID" exec-out run-as "$PACKAGE_NAME" cat "/data/data/$PACKAGE_NAME/$subfolder/$f" > "$DEST_DIR/$target_subdir/$f"
            else
                timeout 30s adb -s "$DEVICE_ID" exec-out su -c "cat /data/data/$PACKAGE_NAME/$subfolder/$f" > "$DEST_DIR/$target_subdir/$f"
            fi
            
            # Verify file was copied and show size
            if [ -f "$DEST_DIR/$target_subdir/$f" ]; then
                size=$(stat -f%z "$DEST_DIR/$target_subdir/$f" 2>/dev/null || stat -c%s "$DEST_DIR/$target_subdir/$f" 2>/dev/null || echo "unknown")
                if [ "$size" = "unknown" ]; then
                    echo "         -> Done"
                else
                    size_kb=$((size / 1024))
                    echo "         -> Done (${size_kb} KB)"
                fi
            else
                echo "         -> [!] Failed to copy"
            fi
        done
        
        echo "[+] Copied $file_count file(s) from $subfolder"
    }

    echo ""
    echo "[*] ========================================"
    echo "[*] Backing up databases..."
    echo "[*] ========================================"
    copy_files "databases" "databases"
    
    echo ""
    echo "[*] ========================================"
    echo "[*] Backing up shared preferences..."
    echo "[*] ========================================"
    copy_files "shared_prefs" "shared_prefs"

    # Generate Stats if sqlite3 is available locally
    DB_PATH="$DEST_DIR/databases/weather_database"
    if [ -f "$DB_PATH" ] && command -v sqlite3 &>/dev/null; then
        echo ""
        echo "[*] Analyzing database..."
        {
            echo "Database: weather_database"
            echo "Record Counts:"
            sqlite3 "$DB_PATH" "SELECT '  - Weather records: ' || count(*) FROM weather_data;" 2>/dev/null || echo "  - weather_data: Table not found"
            sqlite3 "$DB_PATH" "SELECT '  - Forecast snapshots: ' || count(*) FROM forecast_snapshots;" 2>/dev/null || echo "  - forecast_snapshots: Table not found"
            sqlite3 "$DB_PATH" "SELECT '  - Hourly forecasts: ' || count(*) FROM hourly_forecasts;" 2>/dev/null || echo "  - hourly_forecasts: Table not found"
        } > "$DEST_DIR/stats.txt"
        echo "[+] Statistics written to stats.txt"
        cat "$DEST_DIR/stats.txt"
    else
        if [ ! -f "$DB_PATH" ]; then
            echo "[!] Database file not found: $DB_PATH"
        else
            echo "[!] sqlite3 command not available. Skipping database analysis."
        fi
    fi

    # Create metadata
    echo ""
    echo "[*] Creating metadata file..."
    {
        echo "Backup Time: $(date)"
        echo "Device ID: $DEVICE_ID"
        echo "Model: $MODEL"
        echo "Backup Timestamp: $TIMESTAMP"
    } > "$DEST_DIR/metadata.txt"
    echo "[+] Metadata written to metadata.txt"
    
    echo ""
    echo "[*] Capturing last 1000 lines of logcat..."
    timeout 20s adb -s "$DEVICE_ID" logcat -d -t 1000 > "$DEST_DIR/logcat.txt" 2>/dev/null
    if [ -f "$DEST_DIR/logcat.txt" ] && [ -s "$DEST_DIR/logcat.txt" ]; then
        logcat_lines=$(wc -l < "$DEST_DIR/logcat.txt")
        logcat_size=$(stat -f%z "$DEST_DIR/logcat.txt" 2>/dev/null || stat -c%s "$DEST_DIR/logcat.txt" 2>/dev/null || echo "0")
        logcat_size_kb=$((logcat_size / 1024))
        echo "[+] Logcat captured ($logcat_lines lines, ${logcat_size_kb} KB)"
    else
        echo "[!] Failed to capture logcat"
    fi

    echo ""
    echo "[+] ========================================"
    echo "[+] Backup complete for device $DEVICE_ID"
    echo "[+] Destination: $DEST_DIR"
    echo "[+] ========================================"
    echo ""
done

echo "============================================================"
echo "[+] ========================================"
echo "[+] BACKUP SUMMARY"
echo "[+] ========================================"
echo "[+] Devices processed: $DEVICE_COUNT"
echo "[+] Backup location: $BACKUP_ROOT/"
echo "[+] Timestamp: $TIMESTAMP"
echo "[+] ========================================"
echo ""
echo "You can analyze the backups using: scripts/analyze_fetches.py"
echo "To view database contents: sqlite3 <backup_path>/databases/weather_database"