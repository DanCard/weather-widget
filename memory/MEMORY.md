# Weather Widget - Key Learnings

###############################################################################
# !!! CRITICAL SAFETY WARNING !!!
# NEVER run 'connectedDebugAndroidTest' or any command that uninstalls/reinstalls
# the app on PHYSICAL DEVICES:
#   - Pixel 7 Pro (2A191FDH300PPW)
#   - Samsung (RFCT71FR9NT)
# DOING SO WILL DELETE ALL HOME SCREEN WIDGETS AND DATA.
# Always isolate the emulator (e.g., -s emulator-5554) for instrumented tests.
###############################################################################

## Rate Limiter Bug (2026-02-05)
- `WeatherRepository.lastNetworkFetchTime` was set BEFORE the fetch, so failed fetches blocked retries
- Fixed: save previous value, restore on failure (both null-result and exception cases)
- New log tags: `NET_FETCH_FAIL` (both APIs null), `NET_FETCH_ERROR` (exception thrown)
- Tests added in `WeatherRepositoryTest.kt` for rate-limit-reset-on-failure

## Hourly Graph Label Overlap (2026-02-05)
- `HourlyGraphRenderer.kt` had no overlap detection for temperature labels
- Fixed: priority-ordered list (low > high > start > end) + `RectF.intersects()` collision detection
- Removed `pastHighIndex` (redundant), removed NOW-line proximity suppression (over-engineering)
- Debug logging kept temporarily — remove after a few days of monitoring

## API Data Differences
- NWS returns integer temps, Open-Meteo returns decimal temps
- NWS updates forecasts throughout the day, so stale data can show different values
- `buildHourDataList` falls back: preferred source -> SOURCE_GENERIC_GAP -> firstOrNull()
- Fallback logging added in `WeatherWidgetProvider.kt`

## Build & Test
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "..."`
- **Safety:** NEVER run instrumented tests (`connectedDebugAndroidTest`) on physical devices (Pixel/Samsung). It re-installs the app and deletes all home screen widgets. Always use the emulator for instrumented tests.
- Backup DBs: `python3 scripts/backup_databases.py` → query with local `sqlite3`
- No sqlite3 on Pixel device — must pull DB files to query locally
- `app_logs` table timestamp is epoch millis (not string), query with `datetime(timestamp/1000, 'unixepoch', 'localtime')`

## Graph Smoothness & Clutter (2026-02-13)
- NWS data often has "stair-steps" (plateaus).
- **Smoothness:** Applied 3 iterations of a weighted moving average `[0.25, 0.5, 0.25]` to "melt" these steps.
- **Wobble Fix:** Updated `GraphRenderUtils` to use monotone-aware tangents (setting `dy=0` on plateaus/extrema) to prevent spline overshoots.
- **Label De-cluttering:** 
    - Updated `findLocalExtremaIndices` to find the center of a plateau rather than labeling both ends.
    - Added value de-duplication: skip labeling a percentage (e.g., 35%) if it was already labeled within the last 5 hours.
