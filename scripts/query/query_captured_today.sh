#!/bin/bash
# Query how many daily forecasts were CAPTURED today (fetchedAt is today)

TODAY_DATE=$(date +%Y-%m-%d)
BACKUP_ROOT="backups"

# Find all weather databases in the backup directory
DB_PATHS=$(ls -1 backups/*/databases/weather_database 2>/dev/null)

if [ -z "$DB_PATHS" ]; then
    echo "Error: No backups found in $BACKUP_ROOT"
    exit 1
fi

echo "================================================================"
echo "DAILY FORECASTS CAPTURED TODAY ($TODAY_DATE)"
echo "This counts records where 'fetchedAt' is today."
echo "================================================================"
printf "%-30s | %-12s | %-12s
" "Device/Backup" "Snapshots" "Hourly Pts"
echo "----------------------------------------------------------------"

for DB in $DB_PATHS; do
    FOLDER=$(echo "$DB" | cut -d'/' -f2)
    
    # Get counts where fetchedAt (ms) matches today's date in local time
    # We use date('fetchedAt'/1000, 'unixepoch', 'localtime')
    STATS=$(sqlite3 "$DB" <<EOF
    SELECT 
        (SELECT count(*) FROM forecast_snapshots WHERE date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE'),
        (SELECT count(*) FROM hourly_forecasts WHERE date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE');
EOF
    )
    
    SNAPSHOTS=$(echo "$STATS" | cut -d'|' -f1)
    HOURLY=$(echo "$STATS" | cut -d'|' -f2)
    
    printf "%-30s | %-12s | %-12s
" "${FOLDER:0:30}" "$SNAPSHOTS" "$HOURLY"
done
echo "----------------------------------------------------------------"
