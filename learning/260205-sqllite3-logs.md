# SQLite3 Log Queries for NWS Diagnostics

These queries read persistent diagnostics from `app_logs` in a backup database.

## Main Query

```bash
sqlite3 backups/<latest>/databases/weather_database "
SELECT datetime(timestamp/1000,'unixepoch','localtime') AS ts, tag, message
FROM app_logs
WHERE tag IN ('NWS_PERIOD_SUMMARY','NWS_TODAY_SOURCE','NWS_TODAY_TRANSITION')
ORDER BY timestamp DESC
LIMIT 100;"
```

## What Each Tag Means

- `NWS_PERIOD_SUMMARY`
  - Compact summary of NWS forecast periods captured for that fetch.
  - Includes first 8 periods with `name`, `startTime`, `temperature`, and day/night flag.

- `NWS_TODAY_SOURCE`
  - Shows today's high/low and where each came from.
  - Source values:
    - `OBS:<station>` from observation station data
    - `FCST:<period>@<startTime>` from forecast period data

- `NWS_TODAY_TRANSITION`
  - Logged when today's NWS values change.
  - Shows `previous -> current` high/low values and previous snapshot fetch time.

## Tip: Use the Newest Backup Automatically

```bash
LATEST_DB=$(ls -1dt backups/*/databases/weather_database | head -n 1)
sqlite3 "$LATEST_DB" "
SELECT datetime(timestamp/1000,'unixepoch','localtime') AS ts, tag, message
FROM app_logs
WHERE tag IN ('NWS_PERIOD_SUMMARY','NWS_TODAY_SOURCE','NWS_TODAY_TRANSITION')
ORDER BY timestamp DESC
LIMIT 100;"
```
