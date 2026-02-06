#!/bin/bash
# Query NWS forecast distribution captured today (counts per target day)

TODAY_DATE="2026-02-04"
BACKUP_ROOT="backups"

# Find all weather databases in the backup directory
DB_PATHS=$(ls -1 backups/*/databases/weather_database 2>/dev/null)

if [ -z "$DB_PATHS" ]; then
    echo "Error: No backups found in $BACKUP_ROOT"
    exit 1
fi

echo "=========================================================================="
echo "NWS DAILY FORECASTS CAPTURED TODAY ($TODAY_DATE)"
echo "Values = Count of NWS snapshots fetched today for each target date."
echo "=========================================================================="

# Create header with +0 to +6 days
printf "%-25s |" "Device"
for i in {0..6}; do
    TARGET=$(date -d "$TODAY_DATE + $i days" +%m/%d)
    printf " %-7s |" "+$i ($TARGET)"
done
echo ""
echo "--------------------------------------------------------------------------"

for DB in $DB_PATHS; do
    FOLDER=$(echo "$DB" | cut -d'/' -f2)
    DEV_NAME=$(echo "$FOLDER" | cut -d'_' -f3)
    [ -z "$DEV_NAME" ] && DEV_NAME=$(echo "$FOLDER" | cut -d'_' -f2)
    
    printf "%-25s |" "${DEV_NAME:0:25}"
    
    for i in {0..6}; do
        TARGET=$(date -d "$TODAY_DATE + $i days" +%Y-%m-%d)
        
        COUNT=$(sqlite3 "$DB" "SELECT count(*) FROM forecast_snapshots WHERE source = 'NWS' AND targetDate = '$TARGET' AND date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE';")
        
        printf " %-7s |" "$COUNT"
    done
    echo ""
done
echo "=========================================================================="
echo ""
echo "Note: The sm-f936u1 (Galaxy Z Fold 4) showing thousands suggests it's"
echo "repeatedly fetching and storing forecasts for the same days."
