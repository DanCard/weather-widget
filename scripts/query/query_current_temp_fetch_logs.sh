#!/bin/bash
# Summarize CURR_FETCH logs from app_logs and estimate fetch cadence.
#
# Usage:
#   scripts/query/query_current_temp_fetch_logs.sh [optional_db_path]

set -euo pipefail

DB_PATH="${1:-}"

if [ -z "$DB_PATH" ]; then
    DB_PATH=$(
        find backups -type f -name weather_database -printf '%T@ %p\n' 2>/dev/null \
            | sort -nr \
            | head -1 \
            | cut -d' ' -f2-
    )
fi

if [ -z "${DB_PATH:-}" ] || [ ! -f "$DB_PATH" ]; then
    echo "Error: weather_database not found."
    echo "Pass a DB path, for example:"
    echo "  scripts/query/query_current_temp_fetch_logs.sh backups/live_emulator-5556_20260225_1750/databases/weather_database"
    exit 1
fi

echo "DB: $DB_PATH"
echo

echo "Counts by CURR_FETCH tag:"
sqlite3 "$DB_PATH" "
SELECT tag, COUNT(*) AS count
FROM app_logs
WHERE tag LIKE 'CURR_FETCH%'
GROUP BY tag
ORDER BY count DESC, tag;
"
echo

echo "Latest CURR_FETCH events (up to 40):"
sqlite3 "$DB_PATH" "
SELECT datetime(timestamp/1000, 'unixepoch', 'localtime') AS local_time, tag, level, message
FROM app_logs
WHERE tag LIKE 'CURR_FETCH%'
ORDER BY timestamp DESC
LIMIT 40;
"
echo

echo "CURR_FETCH_START interval stats (seconds):"
sqlite3 "$DB_PATH" "
WITH starts AS (
    SELECT
        timestamp,
        LAG(timestamp) OVER (ORDER BY timestamp) AS prev_timestamp
    FROM app_logs
    WHERE tag = 'CURR_FETCH_START'
),
diffs AS (
    SELECT (timestamp - prev_timestamp) / 1000.0 AS diff_seconds
    FROM starts
    WHERE prev_timestamp IS NOT NULL
)
SELECT
    COUNT(*) AS samples,
    CASE WHEN COUNT(*) = 0 THEN 'n/a' ELSE CAST(ROUND(MIN(diff_seconds), 1) AS TEXT) END AS min_s,
    CASE WHEN COUNT(*) = 0 THEN 'n/a' ELSE CAST(ROUND(AVG(diff_seconds), 1) AS TEXT) END AS avg_s,
    CASE WHEN COUNT(*) = 0 THEN 'n/a' ELSE CAST(ROUND(MAX(diff_seconds), 1) AS TEXT) END AS max_s
FROM diffs;
"
