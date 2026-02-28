#!/bin/bash
# Query the live device database for NWS forecast entries for today's target date.
# Usage: ./scripts/query/query_nws_today_forecasts.sh

TODAY_DATE=$(date +%Y-%m-%d)
DEVICE_ID=""
USE_BACKUP=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)
            DEVICE_ID="${2:-}"
            shift 2
            ;;
        --backup)
            USE_BACKUP="true"
            DB_PATH="${2:-}"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--device <device_id>] [--backup [path_to_db]]"
            echo ""
            echo "Options:"
            echo "  --device <id>  Query specific device (default: first connected device)"
            echo "  --backup        Use backup DB instead of live device (optional path)"
            echo ""
            echo "Examples:"
            echo "  $0                                    # Query live device"
            echo "  $0 --device RFCT71FR9NT               # Query specific device"
            echo "  $0 --backup                           # Use latest backup"
            echo "  $0 --backup backups/20260227_.../databases/weather_database"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [--device <device_id>] [--backup [path_to_db]]"
            exit 1
            ;;
    esac
done

# Determine database path
if [[ -n "$USE_BACKUP" ]]; then
    if [[ -z "$DB_PATH" ]]; then
        DB_PATH=$(ls -td backups/*/databases/weather_database 2>/dev/null | head -n 1)
    fi
    if [[ ! -f "$DB_PATH" ]]; then
        echo "Error: Backup database not found at $DB_PATH"
        exit 1
    fi
    DB_SOURCE="backup ($DB_PATH)"
else
    # Use live device database
    if [[ -z "$DEVICE_ID" ]]; then
        DEVICE_ID=$(adb devices | sed '1d;/^$/d' | head -n 1 | awk '{print $1}')
        if [[ -z "$DEVICE_ID" ]]; then
            echo "Error: No device connected"
            exit 1
        fi
    fi
    DB_PATH="/tmp/weather_database_query_$(date +%s).db"
    adb -s "$DEVICE_ID" shell "run-as com.weatherwidget cat /data/data/com.weatherwidget/databases/weather_database" > "$DB_PATH"
    if [[ ! -s "$DB_PATH" ]]; then
        echo "Error: Failed to pull database from device $DEVICE_ID"
        rm -f "$DB_PATH"
        exit 1
    fi
    DB_SOURCE="live device ($DEVICE_ID)"
fi

echo "======================================================================="
echo "NWS FORECASTS FOR TODAY"
echo "Target Date: $TODAY_DATE"
echo "Source: $DB_SOURCE"
echo "Generated: $(date)"
echo "======================================================================="
echo ""

echo "1) Summary"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT
  'Total NWS forecast entries for today: ' || COUNT(*) AS total_entries,
  'Earliest forecastDate: ' || MIN(forecastDate) AS earliest_forecast,
  'Latest fetchedAt: ' || MAX(datetime(fetchedAt/1000, 'unixepoch', 'localtime')) AS latest_fetch
FROM forecasts
WHERE targetDate = '$TODAY_DATE' AND source = 'NWS';
EOF

echo ""
echo "2) Entries fetched today"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT
  datetime(fetchedAt/1000, 'unixepoch', 'localtime') AS fetched_local,
  forecastDate,
  highTemp,
  lowTemp,
  condition,
  precipProbability
FROM forecasts
WHERE targetDate = '$TODAY_DATE' AND source = 'NWS'
  AND date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE'
ORDER BY fetchedAt DESC;
EOF

echo ""
echo "3) All entries by forecastDate"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT
  forecastDate,
  COUNT(*) as count,
  MIN(highTemp) as min_high,
  MAX(highTemp) as max_high,
  MIN(lowTemp) as min_low,
  MAX(lowTemp) as max_low
FROM forecasts
WHERE targetDate = '$TODAY_DATE' AND source = 'NWS'
GROUP BY forecastDate
ORDER BY forecastDate DESC;
EOF

# Clean up temp file if live device was used
if [[ -z "$USE_BACKUP" ]]; then
    rm -f "$DB_PATH"
fi
