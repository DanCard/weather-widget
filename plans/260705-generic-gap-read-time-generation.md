# Stop persisting GENERIC_GAP rows — generate climate-normal gap fill at read time

## Context

GENERIC_GAP rows (climate-normal filler for far-future days) are currently materialized into the Android `forecasts` table by `fetchClimateNormalsGap()` at fetch time. They are pure derived data — an interpolation of the 12-monthly-row `climate_normals` table — yet they live in a table with forecast semantics, forcing hacks (`forecastDate = targetDate`, `isClimateNormal` exclusion filters in the snapshot/accuracy/daily-history writers) and creating staleness/leak hazards. **The desktop app already implements the target design**: it never persists daily gap rows and synthesizes them in memory at read time (`DesktopWeatherRepository.appendClimateNormalGaps()`, desktop/.../DesktopWeatherRepository.kt:595) via the shared `ClimateNormals.expandMonthlyToDaily`. This change brings Android to the same model and extracts the merge into `:shared` so both platforms converge (per standing preference to share Android/desktop logic).

Scope is **daily-only**: hourly GENERIC_GAP labels are in-memory synthetic points from the stitcher (`GraphDataLoader.kt:145`), never persisted — untouched. Desktop needs no schema change (SCHEMA_VERSION stays 8).

**Key gotchas (verified):**
- `WeatherSource.GENERIC_GAP.id == "Generic"`, NOT `"GENERIC_GAP"` — cleanup SQL must bind the enum id or use `isClimateNormal = 1`.
- `WeatherWidgetProvider.loadStartupData`'s `thirtyDays` variable is actually **today+7** — per-site horizons must match each query's end date.
- `DailyViewLogic.kt:330-335` picks `maxByOrNull { it.fetchedAt }` among displaySource+gap candidates → generated rows must carry `fetchedAt = 0L` / `batchFetchedAt = 0L` so real rows always win.
- Coverage rule: fill starts after **min-across-sources** max coverage (not "dates with no rows"), else short-coverage display sources (e.g. NWS 7d vs OM 16d) lose fallback bars for days 8–16.

## Steps

### 1. Shared pure merge — `shared/src/main/kotlin/com/weatherwidget/shared/util/ClimateNormals.kt`

Add `data class GapDay(date: LocalDate, highTemp: Float, lowTemp: Float)` and:

```kotlin
fun fillGaps(
    coveredDates: Set<LocalDate>,
    normalsByMonthDay: Map<MonthDay, Pair<Float, Float>>,
    today: LocalDate,
    horizonDays: Long,
): List<GapDay>
```

One GapDay per date in `[today, today+horizonDays]` not in `coveredDates` and having a normal for its MonthDay. Empty normals → empty list. Body = the loop from DesktopWeatherRepository.kt:595-622 minus the DailyForecast mapping.

### 2. Desktop refactor (behavior-neutral) — `desktop/.../DesktopWeatherRepository.kt`

`appendClimateNormalGaps()` (:595-622) delegates to `ClimateNormals.fillGaps(existingDates, normals, today, GAP_HORIZON_DAYS)`, maps `GapDay` → `DailyForecast(condition = "Historical Avg", isClimateNormal = true)`. Nothing else changes.

### 3. New Android helper — `app/src/main/java/com/weatherwidget/data/repository/ClimateGapFiller.kt` (new file)

`class ClimateGapFiller(private val climateNormalDao: ClimateNormalDao)` — cache-only (12-row query + `expandMonthlyToDaily`), **never network**:
- `cachedNormalsByMonthDay(lat, lon)` — empty map if normals not cached yet.
- `gapRows(lat, lon, locationName, coveredDates, today, horizonDays): List<ForecastEntity>` — entity mapping mirrors old ForecastRepository.kt:1017-1030: `targetDate = forecastDate = epochMs`, `LocationMatch.quantize` coords, `condition = "Historical Avg"`, `isClimateNormal = true`, `source = WeatherSource.GENERIC_GAP.id`, plus `fetchedAt = 0L, batchFetchedAt = 0L`.
- `appendGaps(rows, lat, lon, today, horizonDays)` — covered set = all dates ≤ min-across-sources max targetDate (excluding GENERIC_GAP rows) **plus** dates already carrying a gap row (idempotent; tolerates leftover persisted rows during upgrade window). No real rows → covered = ∅ (fill from today).
- `appendGapsToSnapshots(snapshots, lat, lon, locationName, today, horizonDays)` — adds gap rows only under future date keys absent from the map.

Extract the covered-set computation as an internal pure function for direct unit tests (no mocking framework — project convention). DI: none needed; `ForecastRepository` already receives `climateNormalDao` (AppModule.kt:206) → construct in class body; Provider/Router/Worker construct from `WeatherDatabase.getDatabase(context).climateNormalDao()`.

### 4. ForecastRepository — `app/.../data/repository/ForecastRepository.kt`

1. `getCachedData` (:1226-1227): wrap result in `gapFiller.appendGaps(..., horizonDays = CACHE_FORECAST_DAYS)`. Covers Worker weatherList + all `getWeatherData` return paths; fetch-throttle guards keep today-identical semantics.
2. Fetch path (:198-221): delete `maxCoverageDates` block + `fetchClimateNormalsGap` call + `forecastDao.insertAll(climateGaps)`; replace with best-effort cache warm: `runCatching { getHistoricalNormalsByMonthDay(latitude, longitude) }` (it already fetches+caches on miss; network stays fetch-path-only). Keep `- WeatherSource.GENERIC_GAP` at :191.
3. Delete `fetchClimateNormalsGap` (:1002-1035). Keep `getHistoricalNormalsByMonthDay` unchanged.
4. `getCachedDataBySource` (:1229-1257) — keep + reimplement (WeatherRepository.kt:71 delegates; dedup test calls it): replace the gap DB query (:1232) with `gapFiller.gapRows(coveredDates = liveSourceData dates, ...)`; merge logic unchanged.
5. `cleanOldData()` (:~1280, runs every fetch at :223): add `forecastDao.deleteClimateNormalRows(WeatherSource.GENERIC_GAP.id)` — permanent, idempotent purge; no Room version bump (no schema change). Keep `isClimateNormal` column + enum.

New DAO query in `app/.../data/local/ForecastDao.kt`:
```kotlin
@Query("DELETE FROM forecasts WHERE source = :source OR isClimateNormal = 1")
suspend fun deleteClimateNormalRows(source: String)
```

### 5. WeatherWidgetProvider — `app/.../widget/WeatherWidgetProvider.kt`

Pass a `ClimateGapFiller` into `loadStartupData` (:258+): wrap weatherList with `appendGaps(..., horizonDays = 7)` (matches the misnamed `thirtyDays` = today+7 window) and the snapshot map with `appendGapsToSnapshots(..., horizonDays = 7)` using `latestWeather.locationName`. Leave `activeSources + GENERIC_GAP.id` (:170) — harmless no-op post-purge, tolerant during upgrade window.

### 6. WidgetIntentRouter — `app/.../widget/handlers/WidgetIntentRouter.kt`

- `:164` (daily navigation weatherList): `appendGaps(..., horizonDays = DAILY_FORECAST_DAYS)` — preserves right-nav bound (today+30).
- `:733` (refreshDailyView finalWeatherList): `appendGaps` unconditionally (idempotent); `:735` recentSnapshots: `appendGapsToSnapshots`. Leave `:734` pastSnapshots (past-only) alone.
- `:876` (hourly view, today-only): do NOT fill — gap rows never selected there.
- `:488` (graph daily by source): unchanged.

### 7. WeatherWidgetWorker — `app/.../widget/WeatherWidgetWorker.kt`

`fetchForecastSnapshots` (:339-357): after grouping, `appendGapsToSnapshots(..., horizonDays = 7)` (matches recentEnd = today+7). Main weatherList already covered via step 4.1. Writers stay safe: snapshot writer (:856) and daily-history (:314, :474) `isClimateNormal` guards remain as backstop; generated rows only join render-bound lists.

### 8. Tests

- **Add** `shared/src/test/.../util/ClimateNormalsTest.kt` cases for `fillGaps`: covered dates skipped, inclusive horizon bounds, empty normals → empty, missing MonthDay skipped, covered=∅ fills today..horizon.
- **Create** `app/src/test/.../data/repository/ClimateGapFillerTest.kt` (Robolectric + real Room, per WeatherGapIntegrationTest pattern): seed 12 `ClimateNormalEntity` rows; fill starts after min-across-sources coverage; empty normals → no rows (offline-safe); dedupe vs pre-existing gap rows; field values (`source == "Generic"`, `fetchedAt == 0L`, quantized coords); `appendGaps` idempotence.
- **Rework** `WeatherGapTest.kt` (:113-134): mock normals DAO instead of `getForecastsInRangeBySource(GENERIC_GAP)` (mockk already used in that file — fine for existing tests).
- **Rework** `WeatherGapIntegrationTest.kt`: seed `climateNormalDao().insertAll(...)` instead of inserting gap ForecastEntity rows (:97-99, :116, :143-144); exact-list assertions become per-date (generation now extends to today+30).
- Verify unchanged: `ForecastSnapshotDeduplicationTest`, `DailyGapFallbackGraphIntegrationTest`, `DailyViewHandlerTest`, `DailyViewHandlerFallbackTest`, `DailyViewLogicTest`, app `ClimateNormalsTest`, `TestData.kt`.
- Watch count-based assertions in fetch-path tests (`OpenMeteoIntegrationTest`, `WeatherRepositoryRateLimitIntegrationTest`, `NwsPrecipAmountIntegrationTest`, `ForecastDeduplicationBugReproTest`, `WeatherRepositoryNwsParallelTest`).

## Verification

```bash
./gradlew :shared:test
./gradlew :desktop:test
./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.WeatherGapTest" \
  --tests "com.weatherwidget.data.repository.WeatherGapIntegrationTest" \
  --tests "com.weatherwidget.data.repository.ClimateGapFillerTest" \
  --tests "com.weatherwidget.data.repository.ForecastSnapshotDeduplicationTest" \
  --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest" \
  --tests "com.weatherwidget.widget.DailyGapFallbackGraphIntegrationTest"
./gradlew testDebugUnitTest   # full suite
```

Manual Android (`./gradlew installDebug`; never `pm clear`):
1. Force refresh; pull DB (`python3 scripts/backup_databases.py`): `SELECT COUNT(*) FROM forecasts WHERE source='Generic'` → 0; `climate_normals` has 12 rows.
2. Daily view: navigate right past real coverage — days > today+2 show "Historical Avg" fallback bars out to +30; days ≤ today+2 never do.
3. Airplane mode + process kill → widget still renders fallback days from cache (proves read-time gen is offline-safe).
4. Switch display source to short-coverage NWS: days between NWS coverage end and OM coverage end still show fallback.

Desktop: `scripts/buildStart-desktop.sh`; future fallback bars unchanged.

## Accepted behavior changes (intentional, note in commit)

- Generated rows use `fetchedAt = 0L` → real forecast rows always beat gap rows in `maxByOrNull(fetchedAt)` contests (small display improvement over persisted rows with fresh timestamps).
- Gap coverage computed from loaded rows at read time (per-context) instead of fetch-time global — equal-or-better for display, test-visible only.
- Old persisted gap rows survive until first post-upgrade fetch runs `cleanOldData()`; `appendGaps` date-dedupe prevents doubles meanwhile.
