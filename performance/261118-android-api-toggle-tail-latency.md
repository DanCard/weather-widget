# Android: eliminate 1–1.9 s API-toggle spikes on Pixel 7 Pro

**Date:** 2026-11-18
**Status:** implemented & verified (2026-11-18)
**Scope:** `:app` (`DailyActualsStore`, `DailyInteractionRenderer`, `HeaderWidthChecker`, `WeatherRepository` / sync invalidation), `:shared` (none required; existing `DailyActualsAssembler` and `HeaderContent` reused as-is)

## Report

"Seems slower on pixel than fold-4"
"Can you look again at logs? Again one touch seemed extra slow. Can we optimize for the extra slow ones?" (user, 2026-11-18)

## Evidence

Pixel 7 Pro (`2A191FDH300PPW`, debug build, 3 widgets: 78/79 on NWS, 88 toggled across all 7 sources) vs Samsung Fold 4 (`RFCT70G352Y`):

### 1. Live `SOURCE_SWITCH_E2E` (`15:25:51–15:26:09`, Pixel 7 Pro)

| Time | Transition | `paintMs` | `refresh` |
|---|---|---:|---|
| 15:25:51 | `NWS → SILURIAN` | **1,887 ms** | `FRESH` |
| 15:25:52 | `SILURIAN → OPEN_METEO` | **1,030 ms** | `QUEUED` |
| 15:25:54 | `OPEN_METEO → WEATHER_API` | **907 ms** | `FRESH` |
| 15:25:57 | `WEATHER_API → OPEN_WEATHER_MAP` | **819 ms** | `FRESH` |
| 15:26:04 | `OPEN_WEATHER_MAP → GOOGLE_WEATHER` | **923 ms** | `FRESH` |
| 15:26:06 | `GOOGLE_WEATHER → TOMORROW_IO` | **867 ms** | `FRESH` |
| 15:26:09 | `TOMORROW_IO → NWS` | **1,249 ms** | `FRESH` |

*(Fold 4 taps on the same build: 553–684 ms.)*

### 2. Per-stage `logcat` breakdown of the 1,887 ms tap (`15:25:51`, widget 88, `NWS → SILURIAN`)

```
DAILY_ACTUALS_PERF readMs=134 assembleMs=941 obs=3749 pastRows=184 groups=2 blended=2 skipped=2
                   blendMs=205 blendObs=3024 precipMs=307 rainReductions=2 groupingMs=5 aggregateMs=529
DailyViewHandler   widget=88 resolveMs=3 prepareMs=61 renderMs=58 otherMs=211 totalMs=333
DAILY_INTERACTION_PERF widget=88 action=TOGGLE_API metadata=source=SILURIAN preloaded=false
                   dailyDataMs=1341 gapFillMs=5 currentHourlyMs=6 currentResolveMs=154 renderMs=333 totalMs=1839
```

Subsequent taps (`15:25:52`, `15:26:03`) still ran the full `DAILY_ACTUALS_PERF` every time:
```
15:25:51.968 DAILY_ACTUALS_PERF readMs=103 assembleMs=331 obs=3749 groups=2 blended=2 blendMs=60 precipMs=63
15:26:03.649 DAILY_ACTUALS_PERF readMs=80  assembleMs=323 obs=3773 groups=2 blended=2 blendMs=59 precipMs=66
```

## Root causes

1. **`DailyActualsStore.getDailyActualsWithLiveToday` has no in-memory cache (400–1,341 ms on every tap):**
   - `DailyInteractionRenderer` calls `getDailyActualsWithLiveToday` unconditionally on every tap, re-reading `daily_history` + all 3,749 today/yesterday observations (`apis = null`) and re-running the full live temperature blend + METAR precipitation decoding (`ObservedPrecip`) even when zero new observations were stored since the previous tap 1 second earlier.
2. **`DailyInteractionRenderer` passes all widgets' sources instead of only the tapped widget's source:**
   - Tapping widget `88` (`NWS → SILURIAN`) passed `sources = [NWS, SILURIAN]` (because widgets 78/79 display NWS), causing `DailyActualsAssembler` to blend **2 groups** and run `ObservedPrecip` **twice** (`rainReductions=2`, `precipMs=307`, `dailyDataMs=1,341 ms`) just to paint widget `88`. Every subsequent tap on widget `88` re-blended `NWS` again.
3. **`DailyActualsStore.getDailyActualsWithLiveToday` reads `observations` unscoped (`apis = null`):**
   - Even when assembling a single source (e.g. `WEATHER_API`), it reads all 3,749 rows across every provider.
4. **`HeaderWidthChecker.lineInputs` re-queries Room for all 7 sources on every tap (`otherMs = 211–404 ms`):**
   - `HeaderWidthChecker.lineInputs` calls `GraphDataLoader.loadSettledHourlyForecasts(...)` across all visible sources on every widget paint, even though the header rain-% reserve is identical across all sources for the same `(view, lat, lon, widthDp, hour)`.

## Proposed fix

### Part 1 — Scope `DailyInteractionRenderer` to the tapped widget's display source
- In `DailyInteractionRenderer`: when painting a single widget (`appWidgetId`), pass only that widget's displayed source (`listOf(displaySource)`) into `getDailyActualsWithLiveToday(...)` instead of the union of all widgets' sources. Widget `78`/`79` are not being repainted during a tap on widget `88`, so blending `NWS` during a `SILURIAN` tap is wasted work.
- In `DailyActualsStore.getDailyActualsWithLiveToday`: scope `observationDao.getObservationsInRange(..., apis = providerApis)` to the actuals providers of `activeSourceList` (using `ActualsProviderResolver.providerIdFor`, matching `getDailyActualsStored` lines 149–152, plus `METAR` when `NWS` is included) instead of `apis = null`.

### Part 2 — In-memory per-source cache in `DailyActualsStore` (+ borrowed-provider reuse)
- Add a lightweight process-wide in-memory cache (`DailyActualsMemoryCache`) keyed by `(sourceId, quantizedLat, quantizedLon, todayEpochDay, lookbackDays, personalStationWeight, actualsProviderOverride)` with a short TTL (e.g. 5 min) and **explicit invalidation** whenever new observations or `daily_history` rows are persisted (in `ObservationRepository` / `DailyActualsStore` write paths / `WeatherSyncEngine`).
- On `getDailyActualsWithLiveToday`:
  1. Serve any already-cached sources from memory (**0 ms**).
  2. If a requested source borrows a provider whose today entry is already cached (e.g. `GOOGLE_WEATHER` / `OPEN_WEATHER_MAP` borrowing `NWS`), only read/merge its past `daily_history` rows (or reuse the provider's live today row) instead of re-blending observations.
  3. Assemble only the missing sources, merge them into the cache, and return the requested map.
- Works for both Hilt-injected `DailyActualsStore` and `DailyActualsStore.forDatabase(...)` callers (shared singleton/companion cache).

### Part 3 — Provisional stored today row on cold-cache `TOGGLE_API` tap (eliminates the 1,341 ms first-tap spike)
- On `TOGGLE_API` / `HOME` when the tapped source is a cache miss in `DailyActualsMemoryCache`:
  - Check `getDailyActualsStored` (the same fast stored-row path already used on cold start in `performance/261118-cold-start-daily-actuals-cost.md`, ~15–30 ms instead of 400–941 ms) to paint immediately when a stored today row exists, OR keep live blend if no stored today row exists.
  - Because `DailyBlendRecomputeRun` already writes today's `daily_history` row on every observation fetch (and we verified on 2026-11-18 that stored today rows equal the live blend), the widget paints the exact same values in ~20 ms instead of 1,341 ms!
  - *(Note: only for `TOGGLE_API` / `HOME` when memory-cached live actuals are absent; periodic syncs and `REFRESH` still run the live blend and populate `DailyActualsMemoryCache`.)*

### Part 4 — In-memory cache for `HeaderWidthChecker.lineInputs`
- Cache `HeaderWidthChecker.lineInputs(...)` in memory keyed by `(view, quantizedLat, quantizedLon, widthDp, hourBucket, centerHourBucket, visibleSources)` with a short TTL (5 min) and explicit invalidation when hourly forecasts are saved/synced.
- Cuts `DailyViewHandler.otherMs` by eliminating the 7-source Room query + stitch on every API tap.

## Expected impact (Pixel 7 Pro, debug build)

| Scenario | Before (measured) | Expected after |
|---|---:|---:|
| First cold tap (`NWS → SILURIAN`) | **1,887 ms** | **~250–350 ms** |
| Subsequent taps (all 7 APIs) | **819–1,249 ms** | **~180–300 ms** |
| Fold 4 taps | 553–684 ms | **~150–250 ms** |

## Tests & living documentation

- **Unit + Robolectric / Room integration tests:**
  - `DailyActualsStore` integration test (real Room DB): verify per-source caching hits on repeat calls, incremental assembly when a second source is requested, provider-scoped `apis` read, and cache invalidation after observation / `daily_history` writes.
  - `DailyInteractionRenderer` test: verify single-widget tap requests only that widget's display source (not other widgets' sources), and uses stored today row when memory cache misses on `TOGGLE_API`.
  - `HeaderWidthChecker` cache test: verify cache hit across source toggles and invalidation on sync / hour rollover / visible-source change.
- **Living docs:** check [arch/daily-history-extremes.md](file:///home/dcar/projects/weather-widget/arch/daily-history-extremes.md) and [arch/operations.md](file:///home/dcar/projects/weather-widget/arch/operations.md) and update where caching / tap actuals behavior is documented.
- **End-of-task verification:** `./scripts/staggered-tests.sh --install`, then verify live `SOURCE_SWITCH_E2E` and `DAILY_INTERACTION_PERF` on Pixel 7 Pro and Fold 4.
