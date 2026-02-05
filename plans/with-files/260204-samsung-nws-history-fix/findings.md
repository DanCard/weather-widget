# Findings: Samsung NWS History Display Fix

## Device Identification
- **Samsung Device**: `sm-f936u1_RFCT71FR9NT` (Galaxy Z Fold 4)
- **Status**: NWS history (observations) is missing from the database, while Open-Meteo history is present.

## Database State (Samsung)
- `weather_data`: 28 total records.
  - `NWS`: 7 records (all are future forecasts, Feb 4 - Feb 10). `stationId` is NULL.
  - `Open-Meteo`: 21 records (14 forecasts + 7 days of history, Jan 28 - Feb 3).
- `forecast_snapshots`: 42 records.
- `hourly_forecasts`: 660 records.

## API Logs (Samsung `api_log_prefs.xml`)
- **Spamming Issue**: There are 80 "NWS" main log entries within a short period, occurring almost every second.
- **Duration**: NWS main calls have extremely long durations (e.g., 203,197ms, 71,570ms, 66,817ms).
- **Missing NWS-Obs**: There are ZERO `NWS-Obs` entries in the log, despite 100 max entries.
- **Errors**: Some `Unable to resolve host` and `connection pool closed` errors, but many NWS calls are marked as `success: true`.

## Code Investigation
- `fetchFromNws` calls `fetchDayObservations` for 8 days (today + last 7 days).
- `fetchDayObservations` tries up to 5 stations from the cached list.
- **Observation Station Cache**: Present on Samsung, contains 52 stations. First 5: `AW020, KNUQ, KPAO, LOAC1, KSJC`.
- **Potential Issues**:
  1. **Rate Limiting/Spamming**: The device is making too many NWS calls, which might lead to the NWS API throttling or failing silently.
  2. **Timeouts**: The long durations suggest the device is struggling with the volume of calls (40 observation calls per main fetch).
  3. **Station Selection**: The first station `AW020` might be problematic, but it should fall back to `KNUQ`.
  4. **Logging Overwrite**: `NWS-Obs` entries might be pushed out of the `ApiLogger` (100 entries) by the burst of 80 `NWS` main entries.

## Comparison with Pixel
- Pixel 7 Pro has 3 NWS observation records (Feb 1, 2, 3) with `stationId` `KNUQ` and `KSJC`.
- Pixel is NOT spamming NWS calls (based on previous knowledge of `analyze_fetches.py` output).

## Root Cause Analysis (Verified)

- **Primary Cause: Leaky Merge Logic**: The original `mergeWithExisting` only mapped over *new* items from the API. If the API returned only 7 forecast days but the database had 7 history days, the resulting list would contain *only* the 7 new items. When `insertAll` was called with `REPLACE` strategy, the historical records were effectively deleted because they weren't in the new list.

- **Secondary Cause: Transient Network Bursting**: Because `lastNetworkFetchTime` was not persisted, every process restart reset the rate limit. This caused high-frequency "bursts" of NWS calls.

- **Sequential Slowness**: Sequential NWS fetches took up to 200 seconds, making the process a prime target for the Android OS to kill during execution.



## Resolution

1. **Union Merge**: Rewrote merge logic to preserve any date present in either the API OR the Database.

2. **Persistence**: Moved rate-limit timestamp to SharedPreferences.

3. **Parallelism**: Reduced NWS fetch time from ~200s to ~5s using Coroutine parallelism.

4. **Forensics**: Added `app_logs` table to monitor for future data loss.
