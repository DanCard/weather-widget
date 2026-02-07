#!/bin/bash
set -euo pipefail

# Extract today's NWS forecast history from the latest Samsung backup DB.
# Usage:
#   ./scripts/query/query_nws_today_history_latest_samsung.sh
#   ./scripts/query/query_nws_today_history_latest_samsung.sh --db backups/<folder>/databases/weather_database

TODAY_DATE=$(date +%Y-%m-%d)
DB_PATH=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --db)
            DB_PATH="${2:-}"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--db <path_to_weather_database>]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [--db <path_to_weather_database>]"
            exit 1
            ;;
    esac
done

if [[ -z "$DB_PATH" ]]; then
    DB_PATH=$(ls -td backups/*/databases/weather_database 2>/dev/null | grep -E '/[0-9]{8}_[0-9]{6}_sm-[^/]+_[^/]+/databases/weather_database$' | head -n 1 || true)
fi

if [[ -z "$DB_PATH" || ! -f "$DB_PATH" ]]; then
    echo "Error: Could not find latest Samsung backup DB."
    echo "Expected path like: backups/<timestamp>_sm-..._<serial>/databases/weather_database"
    echo "You can pass one explicitly:"
    echo "  $0 --db backups/<folder>/databases/weather_database"
    exit 1
fi

echo "======================================================================="
echo "NWS FORECAST HISTORY FOR TODAY"
echo "Today: $TODAY_DATE"
echo "DB: $DB_PATH"
echo "Generated: $(date)"
echo "======================================================================="
echo ""

echo "1) Session summary (NWS snapshots for targetDate=today, fetched today)"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT
  datetime(fetchedAt/1000, 'unixepoch', 'localtime') AS fetched_local,
  COUNT(*) AS rows_in_session,
  MIN(highTemp) AS min_high,
  MAX(highTemp) AS max_high,
  MIN(lowTemp) AS min_low,
  MAX(lowTemp) AS max_low
FROM forecast_snapshots
WHERE source = 'NWS'
  AND targetDate = '$TODAY_DATE'
  AND date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE'
GROUP BY fetchedAt
ORDER BY fetchedAt ASC;
EOF

echo ""
echo "2) Detailed rows (NWS, targetDate=today, fetched today)"
sqlite3 "$DB_PATH" <<EOF
.mode column
.headers on
SELECT
  datetime(fetchedAt/1000, 'unixepoch', 'localtime') AS fetched_local,
  forecastDate,
  targetDate,
  highTemp,
  lowTemp,
  condition
FROM forecast_snapshots
WHERE source = 'NWS'
  AND targetDate = '$TODAY_DATE'
  AND date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE'
ORDER BY fetchedAt ASC, forecastDate ASC;
EOF

echo ""
echo "3) Raw count"
sqlite3 "$DB_PATH" "SELECT 'rows=' || COUNT(*) FROM forecast_snapshots WHERE source = 'NWS' AND targetDate = '$TODAY_DATE' AND date(fetchedAt/1000, 'unixepoch', 'localtime') = '$TODAY_DATE';"
