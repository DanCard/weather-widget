#!/bin/bash

# Script to query observation derivation logs from the emulator's database.

# Default to emulator-5554 if not specified
DEVICE=${1:-"emulator-5554"}

echo "=== Observation Derivation Report ($DEVICE) ==="

# First, pull the latest database
python3 scripts/backup_databases.py --device $DEVICE > /dev/null

# Find the latest backup directory
BACKUP_DIR=$(ls -td backups/*_sdk_gphone64_x86_64_$DEVICE | head -n 1)

if [ -z "$BACKUP_DIR" ]; then
    echo "Error: No backup directory found for $DEVICE"
    exit 1
fi

DB_PATH="$BACKUP_DIR/databases/weather_database"

# Query the summary
echo ""
echo "--- Latest Derivations ---"
sqlite3 "$DB_PATH" "SELECT datetime(timestamp/1000, 'unixepoch', 'localtime'), message FROM app_logs WHERE tag = 'OBS_DERIVATION' ORDER BY timestamp DESC LIMIT 5;"

# Query the details for the most recent derivation
echo ""
echo "--- Details for Latest Derivation ---"
LATEST_TIMESTAMP=$(sqlite3 "$DB_PATH" "SELECT timestamp FROM app_logs WHERE tag = 'OBS_DERIVATION' ORDER BY timestamp DESC LIMIT 1;")

if [ -n "$LATEST_TIMESTAMP" ]; then
    # Query details within 1 second of the latest derivation log
    sqlite3 "$DB_PATH" "SELECT message FROM app_logs WHERE tag = 'OBS_DERIVATION_DETAIL' AND timestamp BETWEEN ($LATEST_TIMESTAMP - 1000) AND ($LATEST_TIMESTAMP + 1000) ORDER BY message ASC;"
else
    echo "No derivation details found."
fi

echo ""
echo "=== End of Report ==="
