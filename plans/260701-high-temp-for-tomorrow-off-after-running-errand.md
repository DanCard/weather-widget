# Samsung shows 74° for tomorrow, others show 77° — diagnosis & fix

## Context

User suspected an errand (location change) caused the Samsung to show tomorrow's high as 74° (NWS) while other devices show 77°. **The errand is not the cause** — background fetches never use GPS (`WeatherWidgetWorker.kt:150-155` reads stored widget prefs), and every row in the Samsung DB is at home coordinates.

**Confirmed root cause (verified against the pulled Samsung DB + screenshot):** coordinate fragmentation in the `forecasts` table plus a display-selection gap.

- The fetch coordinate hopped by ~1.5 m on June 30 12:26 (per-widget float-jittered stored locations; `doWork` uses `firstNotNullOfOrNull` over widget IDs, so the picked widget's coords can change):
  - **Site B** `37.41682815551758, -122.0889205932617` — last NWS batch June 29 12:26, tomorrow's high = **74°**
  - **Site A** `37.41684341430664, -122.0890045166016` — batches since June 30, freshest NWS = **77°** (July 1 12:14)
- `ForecastDao.getForecastsInRange` (`app/.../data/local/ForecastDao.kt:109-124`) keeps the freshest row **per exact (lat, lon)** inside the ±0.1° `LocationMatch` box → both sites' rows survive (replayed the SQL against the Samsung DB: returns both, stale 74 row first by rowid).
- `DailyViewHandler.kt:260-265` does `groupBy { date }` + `items.find { it.source == displaySource.id }` → **first match wins, no cross-site freshness ranking** → the stale June-29 74° displays. Screenshot confirms: NWS widget, Thu = 74°.

**Answer to "what will it take to self-correct":** it won't, on its own, until each stale date passes. Site B's June 29 batch covers targets through ~July 6; nothing overwrites those rows (new fetches write Site A) and retention deletes by old `targetDate` only. Without a fix the widget can display June-29-vintage forecasts for future days through ~July 6, and the same fragmentation can recur any time the fetch site hops.

The hourly table had this exact bug and was fixed with write-quantization + a shared selector (`LocationMatch.quantize` 3dp, `HourlyForecastSelector`). The `forecasts` table never got the same treatment.

## Plan

Per the user's standing preference, put decision logic in `:shared` and have both platforms delegate.

### 1. Read-side heal (the essential fix): freshest-across-sites selection
- Add a shared pure selector, e.g. `shared/.../shared/forecast/DailyForecastSelector.kt`, modeled on `HourlyForecastSelector` (`shared/src/main/kotlin/com/weatherwidget/shared/actuals/HourlyForecastSelector.kt`): given rows already filtered to the proximity box, keep, **per (targetDate, source), the row with max `batchFetchedAt`** (tie-break: nearest site via `LocationMatch.sameSite`/distance). Log dropped stale-site rows at `Log.v` (permanent verbose tracing per user preference).
- Apply it where `getForecastsInRange` / `getForecastsInRangeForSources` results feed the daily UI — the `WidgetIntentRouter` call sites (~`WidgetIntentRouter.kt:163, :761`) or immediately in the repository wrapper so all consumers heal at once (prefer the repository layer).
- Sweep desktop's daily/forecast JDBC queries for the same per-exact-site `MAX(batchFetchedAt)` subquery pattern and apply the same shared selector.

### 2. Write-side prevention: quantize forecast coords
- Quantize `locationLat/locationLon` with `LocationMatch.quantize()` (3dp) when saving `forecasts` rows (Android repository save path + desktop equivalent), mirroring the hourly fix. New rows collapse to one site (37.417, -122.089); stale-site rows then lose on freshness via the selector.

### 3. Tests (plain JUnit, no mocking — per project testing strategy)
- Selector unit tests in `:shared`: two sites in-box with different `batchFetchedAt` → freshest wins; single site unchanged; tie handling; reproduces the exact Samsung scenario (Site A/B coords, 74 vs 77).

### 4. Memory updates (post-approval, during execution)
- Save feedback memory: when a device needed for debugging isn't visible via adb, say so plainly so the user knows to plug it in (user request from this session).
- Save project memory: forecasts-table coordinate fragmentation + daily first-match selection bug (link `[[hourly_coordinate_fragmentation_fix]]`, `[[widget_fetch_location_decoupled]]`).

## Verification
1. `./gradlew testDebugUnitTest --tests "*DailyForecastSelector*"` (and `:shared:test`).
2. `./gradlew installDebug` (Samsung + Pixel connected; safe — do NOT run `connectedDebugAndroidTest`).
3. Broadcast `ACTION_REFRESH` (or tap a widget) on the Samsung, then screenshot via `adb exec-out screencap` (strip pre-PNG bytes before convert) → Thursday should show **77°** on the NWS widget, matching the Pixel.
4. Re-query the Samsung DB: replayed `getForecastsInRange` + selector semantics should yield exactly one row per (date, source), the July 1 batch.

## Files
- `shared/src/main/kotlin/com/weatherwidget/shared/forecast/DailyForecastSelector.kt` (new) + test
- `app/.../data/repository/WeatherRepository.kt` (apply selector on daily range reads; quantize on forecast save)
- `desktop/` daily query call sites (same selector; find during implementation)
- Reuse: `LocationMatch.quantize`, `LocationMatch.sameSite`, `HourlyForecastSelector` as the structural model
