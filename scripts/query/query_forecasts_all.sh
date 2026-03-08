#!/bin/bash
# Query all weather database backups for forecast information across all devices

TODAY=$(date +%Y-%m-%d)
BACKUP_ROOT="backups"

# Find all weather databases in the backup directory, handling spaces
find "$BACKUP_ROOT" -name "weather_database" -path "*/databases/*" -print0 | while IFS= read -r -d '' DB; do
    FOLDER=$(echo "$DB" | cut -d'/' -f2)
    # Check if DB is valid sqlite file
    if ! sqlite3 "$DB" "PRAGMA integrity_check;" > /dev/null 2>&1; then
        # printf "%-30s | %-10s | %-10s | %-10s\n" "${FOLDER:0:30}" "MALFORMED" "-" "-"
        continue
    fi
    STATS=$(sqlite3 "$DB" "SELECT (SELECT count(*) FROM forecast_snapshots WHERE targetDate = '$TODAY'), (SELECT count(*) FROM hourly_forecasts WHERE dateTime LIKE '$TODAY%'), (SELECT count(*) FROM weather_data WHERE date = '$TODAY');")
    
    SNAPSHOTS=$(echo "$STATS" | cut -d'|' -f1)
    HOURLY=$(echo "$STATS" | cut -d'|' -f2)
    ACTUALS=$(echo "$STATS" | cut -d'|' -f3)
    
    printf "%-30s | %-10s | %-10s | %-10s\n" "${FOLDER:0:30}" "$SNAPSHOTS" "$HOURLY" "$ACTUALS"
done

echo "================================================================"
echo ""
echo "DETAILED COMPARISON (Latest Snapshot per Device)"
echo "----------------------------------------------------------------"
printf "%-25s | %-10s | %-5s / %-5s | %-15s\n" "Device" "Source" "High" "Low" "Last Updated"
echo "----------------------------------------------------------------"

find "$BACKUP_ROOT" -name "weather_database" -path "*/databases/*" -print0 | while IFS= read -r -d '' DB; do
    FOLDER=$(echo "$DB" | cut -d'/' -f2)
    DEV_ID=$(echo "$FOLDER" | cut -d'_' -f2,3)
    
    # Check if DB is valid sqlite file
    if ! sqlite3 "$DB" "PRAGMA integrity_check;" > /dev/null 2>&1; then
        continue
    fi

    # Run query and store results in a temporary file to avoid pipe/EOF issues
    sqlite3 -csv "$DB" "SELECT '$DEV_ID', source, highTemp, lowTemp, datetime(max(fetchedAt)/1000, 'unixepoch', 'localtime') FROM forecast_snapshots WHERE targetDate = '$TODAY' GROUP BY source ORDER BY max(fetchedAt) DESC;" > /tmp/weather_query.csv
    
    while IFS=, read -r dev src high low fetched; do
        # Strip quotes added by -csv mode if present
        dev=$(echo "$dev" | tr -d '"')
        src=$(echo "$src" | tr -d '"')
        printf "%-25s | %-10s | %-5s / %-5s | %-15s\n" "${dev:0:25}" "$src" "$high" "$low" "$fetched"
    done < /tmp/weather_query.csv
done
rm -f /tmp/weather_query.csv
echo "----------------------------------------------------------------"
