# Weather Widget - Key Learnings

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
- Backup DBs: `python3 scripts/backup_databases.py` → query with local `sqlite3`
- No sqlite3 on Pixel device — must pull DB files to query locally
- `app_logs` table timestamp is epoch millis (not string), query with `datetime(timestamp/1000, 'unixepoch', 'localtime')`
