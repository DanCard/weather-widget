# Findings: Samsung High Frequency Fetches

## Codebase Investigation
- `ScreenOnReceiver` triggers a fetch on `ACTION_USER_PRESENT` if charging and data is stale (>30 mins).
- `ScreenOnReceiver` also sends `ACTION_REFRESH` to `WeatherWidgetProvider`.
- `WeatherWidgetProvider`'s `onReceive` for `ACTION_REFRESH` ALSO checks staleness and triggers another fetch.
- `WeatherWidgetProvider.onUpdate` ALWAYS triggered a fetch via `triggerImmediateUpdate` without any staleness check.
- All fetch triggers used `WorkManager.enqueue()` without unique work names, allowing multiple workers to run in parallel.
- `WeatherWidgetWorker` called `weatherRepository.getWeatherData` with `forceRefresh = true` for non-UI-only refreshes, bypassing the repository's internal cache.

## Database Analysis
- `api_log_prefs.xml` on a Samsung device showed bursts of API calls (NWS and Open-Meteo) happening within milliseconds of each other.
- `forecast_snapshots` on Samsung device had over 11,500 redundant rows for a single day (Feb 4).
- NWS fetches were particularly expensive because each "fetch" session for forecasts also triggered 8 separate observation API calls.

## Root Causes Identified
1. **Redundant Triggers**: Both `ScreenOnReceiver` and `WeatherWidgetProvider` triggered fetches for the same event.
2. **Lack of De-duplication**: `WorkManager.enqueue` allowed multiple parallel workers.
3. **Missing Staleness Check in `onUpdate`**: `onUpdate` triggered a fetch every time it was called by the system.
4. **Cache Bypass**: `forceRefresh = true` in `WeatherWidgetWorker` bypassed the 30-min cache.

## Fixes Implemented
1. **Unique Work**: Switched to `enqueueUniqueWork` with `KEEP` for fetches and `REPLACE` for UI updates.
2. **Staleness Threshold**: Enforced 30-minute staleness check in `onUpdate` and `WeatherRepository`.
3. **Rate Limiting**: Added 1-minute minimum interval between network fetches in `WeatherRepository`.
4. **Trigger Consolidation**: `ScreenOnReceiver` now delegates to the provider's `ACTION_REFRESH` logic with a `uiOnly` flag.
5. **Database Cleanup**: Created `scripts/trim_database.py` which removed ~11,000 redundant rows from the Samsung backup.
