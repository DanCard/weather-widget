# Tier 2 — Desktop Forecast Accuracy Tracking

> Builds on Tier 1 persistence (`260602-desktop-persistence-layer.md`, implemented in commits
> d362b98f / 2a75b815 / 130b3a73). This is the Android widget's **headline feature** and is entirely
> absent on desktop. Depends on the DB existing (it does).

## Context / Goal

The Android app compares **1-day-ahead forecasts against actual observed highs/lows** and reports
accuracy stats (avg error, directional bias, max error, % within ±3°F, 0–5 score). Desktop stores
forecasts now but has **no actuals** and **no accuracy calculation**.

Goal: end-to-end accuracy tracking on desktop —
1. Build an **actuals pipeline** (fetch historical observations → compute `daily_extremes`).
2. **Port `AccuracyCalculator`** (+ stats models + observation→actuals resolver) into `:shared`.
3. Surface it in the popup: a **statistics panel** and **inline actual-vs-forecast** on the daily view
   (the desktop analogue of Android's FORECAST_BAR / SIDE_BY_SIDE / DIFFERENCE display modes).

## Prerequisite Tier 1 cleanups (fold in first)

- **Fix `stationId`** in `DesktopWeatherService.fetchNwsForecast`: capture the chosen
  `stations.firstOrNull()?.id` (e.g. `KSFO`) and thread it into `DesktopObservationEntity.stationId`
  instead of `observationStationsUrl.substringAfterLast("/")` (currently the literal `"stations"`).
- **Fix layering inversion**: move `DesktopObservationEntity`/`DesktopDailyExtremeEntity` out of the
  import path of `data/model/ForecastTypes.kt`. Either drop `rawObservations` from `ForecastResult`
  and return observations via a separate channel from the service, or keep the entity in `local` and
  have the model reference a plain domain type. Model package must not depend on `local.desktop`.
- **DB concurrency**: in `DesktopWeatherDatabase.getConnection()` set `PRAGMA journal_mode=WAL` and
  `PRAGMA busy_timeout=5000` so the (future) genmon reader and the app writer don't deadlock.

## 1. Actuals pipeline (the missing core)

**Fetch historical observations.** Today only the single latest observation is fetched. Add a
past-window fetch mirroring `NwsApi.getObservations(stationId, start, end)` (already in `:shared`):
- On `refresh()`, also fetch ~7 days of observations for the chosen station and upsert them into
  `observations` (PK `stationId,timestamp` dedups naturally).
- **Multi-station fallback (port from Android `WeatherRepository`/observation layer):** if the nearest
  station has gaps, try up to ~5 nearby stations from `getObservationStations()`; cache the station
  list ~24h. Keep it simple for v1 — nearest station, fall through to next on empty data.

**Compute `daily_extremes` from stored observations.** Port Android
`ObservationRepository.recomputeDailyExtremesFromStoredObservations` (+ `ObservationBlender` IDW only
if multi-station is in scope; otherwise single-station max/min per UTC day). For each day in the
window: group observations by UTC day → `highTemp = max(temperature)`, `lowTemp = min(temperature)`,
plus precip day/night splits if available → `upsertDailyExtremes(...)`. Call this at the end of
`refresh()` (it currently is **not** called — that's the key gap).

**New DAO queries** (Tier 1 didn't add these — accuracy needs them):
- `getExtremesInRange(startEpoch, endEpoch, lat, lon): List<DesktopDailyExtremeEntity>`
- `getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source): List<DesktopForecastRow>`
- `getObservationsInRange(start, end, lat, lon): List<DesktopObservationEntity>`

## 2. Port the accuracy engine to `:shared`

Move pure logic out of `app/` (Room-coupled) into `shared/.../stats/`:
- `AccuracyCalculator.kt` — port `calculateAccuracy`, `getDailyAccuracyBreakdown`, `calculateScore`
  (thresholds: perfect ≤1°, excellent ≤2°, good ≤3°, fair ≤4°). Backed by the JDBC DAO instead of the
  Room DAOs. Core comparison unchanged: for each actual day `targetDate`, find the forecast with
  `forecastDate = targetDate − 1` for that source; `highError/lowError = forecast − actual`.
- `AccuracyStatistics.kt` — port `ComparisonStatistics` / `DailyAccuracy` data classes (pure; lift
  verbatim).
- The actuals resolver (`extremesToDailyActuals` equivalent) — convert `daily_extremes` rows to the
  `DailyActual(date, highTemp, lowTemp)` shape the calculator consumes.

These are pure functions over DAO results → unit-testable with no mocking (matches project norm).

## 3. Desktop UI (Compose)

- **Statistics panel**: a new window/section (analogue of Android `StatisticsActivity`) showing per
  source: avg high/low error, bias ("runs 2° high"), max error, % within ±3°, score. Reachable from
  the existing tray "Settings"/popup menu.
- **Inline actual-vs-forecast** on the daily view (`DailyForecastGraph`): for past days, overlay the
  actual high/low next to the forecast — pick ONE default style (recommend SIDE_BY_SIDE: `72° (N:68°)`
  or a forecast-vs-actual bar) and make it a `DesktopConfig.accuracyDisplayMode` setting later. Keep
  v1 to a single clear style; don't port all 5 Android modes up front.

## 4. (Included stretch) Yesterday + history navigation

The same actuals+forecast data unlocks browsing. If time allows in this tier, add prev/next-day
navigation to the popup (reuse Android `NavigationUtils` offset logic) so the user can see yesterday's
forecast-vs-actual. Otherwise split into Tier 2b.

## Tests

- DAO: `getExtremesInRange` / `getForecastsInRangeBySource` round-trips against temp-file SQLite.
- `recomputeDailyExtremes`: observations spanning a UTC day → correct high/low.
- `AccuracyCalculator`: seed forecasts (`targetDate=D, forecastDate=D−1`) + extremes for `D` → assert
  avgError, signed bias, maxError, percentWithin3, score. Include a known-bias case.

## Verification

1. `./gradlew :shared:test :desktop:test` green.
2. `./gradlew :desktop:run`; after a refresh, `sqlite3 ~/.local/share/weather-widget/weather.db
   "SELECT * FROM daily_extremes;"` returns real rows (was empty before).
3. Let it run across a day boundary (or seed snapshots) → stats panel shows non-empty NWS accuracy;
   daily view shows actual-vs-forecast on past days.
4. `stationId` column shows real IDs (e.g. `KSFO`), not `"stations"`.

## Reuse map (Android → `:shared`)

- `app/.../stats/AccuracyCalculator.kt`, `AccuracyStatistics.kt` → port (swap Room DAO for JDBC DAO).
- `app/.../util/ObservationResolver.kt`, `ObservationBlender.kt` → actuals resolver / IDW (optional).
- `app/.../data/repository/WeatherRepository.recomputeDailyExtremesFromStoredObservations` → DAO method.
- `app/.../util/NavigationUtils.kt` → history navigation (stretch).
