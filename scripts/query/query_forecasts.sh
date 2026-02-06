#!/bin/bash
# Query the weather database for forecast information

TODAY="2026-02-04"
DB_PATH=$1

if [ -z "$DB_PATH" ]; then
    # Try to find the most recent backup
    DB_PATH=$(ls -td backups/*/databases/weather_database 2>/dev/null | head -n 1)
fi

if [ ! -f "$DB_PATH" ]; then
    echo "Error: Database not found at $DB_PATH"
    echo "Usage: ./scripts/query_forecasts.sh [path_to_db]"
    exit 1
fi

echo "Querying Database: $DB_PATH"
echo "Target Date: $TODAY"
echo "------------------------------------------------"

echo "1. Forecast Snapshots for Today (by Source):"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT source, forecastDate, highTemp, lowTemp, datetime(fetchedAt/1000, 'unixepoch', 'localtime') as fetched
FROM forecast_snapshots 
WHERE targetDate = '$TODAY'
ORDER BY forecastDate DESC, source ASC;
EOF

echo ""
echo "2. Hourly Forecast Counts for Today (by Source):"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT source, COUNT(*) as count
FROM hourly_forecasts 
WHERE dateTime LIKE '$TODAY%'
GROUP BY source;
EOF

echo ""
echo "3. Summary Counts:"
sqlite3 "$DB_PATH" <<EOF
SELECT 'Total snapshots for today: ' || count(*) FROM forecast_snapshots WHERE targetDate = '$TODAY';
SELECT 'Total snapshots MADE today: ' || count(*) FROM forecast_snapshots WHERE forecastDate = '$TODAY';
SELECT 'Actual weather records for today: ' || count(*) FROM weather_data WHERE date = '$TODAY';
EOF
