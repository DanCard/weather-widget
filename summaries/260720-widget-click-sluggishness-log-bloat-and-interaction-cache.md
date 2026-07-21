# Widget click sluggishness — log-write bloat + uncached per-paint loads

Date: 2026-07-20

## Symptom

On a Samsung Z Fold 4 with **5 widget instances**, "several clicks" (nav / view-toggle / API
toggle) made the widgets feel very sluggish. The app's own `app_logs` recorded it directly:
`*_SLOW` rows with `SET_VIEW`/`REFRESH` at **1.1–1.4 s**, `TOGGLE_API`/`GRAPH_NAV` ~300 ms;
722 slow/watchdog events total. Bursts showed one refresh fanning out to all 5 widgets
(`REFRESH_SLOW` ×5 back-to-back) with the user's taps queued behind.

## Root cause

Three compounding factors, biggest first for *click* latency:

1. **Uncached heavy per-paint loads (dominant).** Every interaction repaint rebuilds daily
   actuals from `hourly_forecast_history` (~105k rows) + `observations` (~1.6k rows →
   ~1k candidate points; `TEMP_ACTUALS_PERF buildMs=150–350`), plus ~6 other DB reads.
   `handleDailyNavigation` pays most of this *just to bounds-check* whether a nav is allowed,
   before it even mutates the offset.

2. **`app_logs` write bloat (secondary but real).** The table reached **100,831 rows / 18 MB in
   3 days** (~33k rows/day) on this device; every click also inserts into that indexed table.
   The top tags were pure diagnostics: `TEMP_ACTUALS_DEBUG` (14k), `DAILY_HISTORY_BLEND` (8.6k),
   both write-only (never queried back). `AppLogDao.log()` was inserting **all** levels — passing
   `"VERBOSE"` did NOT keep a row out of the DB (unlike the shared `appLog`/dbLogger boundary,
   which drops VERBOSE).

3. **Concurrency herd (residual).** Each tap `launchAsync`es on an unbounded `Dispatchers.IO`,
   so N rapid taps × M widgets all rebuild at once and contend on CPU + SQLite.

## Fix

**Logging (stop the bloat + the per-click write cost):**
- `AppLogDao.log()` now **skips the DB insert when `level == "VERBOSE"`** (logcat only), matching
  the shared dbLogger boundary. VERBOSE is now a real "don't persist" signal on the DAO path too.
- Downgraded `TEMP_ACTUALS_DEBUG` and `DAILY_HISTORY_BLEND` to VERBOSE (per-paint diagnostics,
  never read back).
- Added `AppLogDao.capToNewest(keep)` (`DELETE ... WHERE id NOT IN (SELECT id ... ORDER BY id
  DESC LIMIT :keep)`), called in `ForecastRepository.cleanOldData()` after the 72 h age cutoff,
  cap = 50 000 rows. Backstop for the age window at high write rates.

**Cache (cut the dominant per-paint cost):**
- New `WidgetInteractionCache` — a 2 s-TTL cache keyed on `(lat, lon quantized 3dp, epochDay)`
  holding the raw daily forecast list + `DailyActualsBySource`. A tap burst across widgets, or
  repeated taps faster than a repaint, shares one load instead of re-querying per tap.
- Wired into `WidgetIntentRouter.refreshDailyView` and the daily-nav bounds check **only** — the
  interaction path. The background/worker paint uses `WidgetRenderer`, so a stale entry can never
  leak into a scheduled/fetch-driven repaint. Correctness is bounded by TTL alone (no explicit
  invalidation needed); a real fetch repaints via the worker path with live data regardless.

## Result (verified on-device)

- Swamp tags: new-code paints wrote **0** persisted rows (was ~23k/day).
- Row cap fired live during a fetch cycle: `app_logs` **101,592 → 50,047** rows (43 → 31 MB).
- Realistic spaced tapping (warm process): daily nav **~1200 ms → ~200–475 ms**.

## Caveat (measurement)

A freshly-installed **debug** build has cold JIT — the paint path runs interpreted (logcat:
`Method exceeds compiler instruction limit ... WeatherWidgetWorker.doWork`), so the first ~dozen
taps read 2–4× slow regardless of any fix. Always warm the process before trusting on-device
paint timings; watch the timing trend fall as ART compiles the hot path.

## Not done (deliberately)

Extreme instant bursts (e.g. 15 broadcasts in <100 ms) still spike ~1.4 s — they defeat the cache
(all miss before the first populates) and thrash the unbounded dispatcher. Flattening that edge
needs **per-widget latest-wins paint coalescing** in `refreshDailyView`/`refreshGraphView` (both
confirmed interaction-only, so coalescing can't drop a scheduled paint). Held off: the realistic
"several clicks" case is fixed and the interaction path is regression-prone. The cache already
makes each surviving paint cheap, which is the groundwork a coalescer would build on.

## Tests

- `app/src/test/java/com/weatherwidget/widget/handlers/WidgetInteractionCacheTest.kt` — TTL hit/miss,
  location/day key separation, 3dp jitter sharing a key.
- Existing `com.weatherwidget.widget.handlers.*` and `WeatherWidgetProvider*` unit suites pass
  (no regressions from the router edits).

## Files

- `app/src/main/java/com/weatherwidget/data/local/AppLogEntity.kt` (VERBOSE gate + `capToNewest`)
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` (cap call + constant)
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` (`DAILY_HISTORY_BLEND` → VERBOSE)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt` (`TEMP_ACTUALS_DEBUG` → VERBOSE)
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` (cache-backed loads)
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetInteractionCache.kt` (new)
- `app/src/test/java/com/weatherwidget/widget/handlers/WidgetInteractionCacheTest.kt` (new)
