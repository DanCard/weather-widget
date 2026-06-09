# Session Log — 2026-06-08

## Summary
Investigated "current temp varies widely across desktop, emulator, Pixel 7 Pro, and Samsung"
(all on NWS). Live logs disproved several hypotheses (delta handling, GPS resolution) and isolated
the real root cause: **`WeatherWidgetWorker` fetched the location of the latest weather *row*
(`getLatestLocation()`), not the widget's *configured* location** — so once the table seeded to the
hard default (Google HQ, `37.422,-122.0841`), every refresh re-fetched the default forever and the
configured GPS location (Avery Drive, `37.4168,-122.089`) was ignored. Fixed the worker to honor the
configured location; verified on all three Android devices. Also fixed `ConfigActivity` GPS resolution
(`lastLocation` → active `getCurrentLocation`), added a foreground GPS auto-heal in `MainActivity`,
extracted a shared `LocationUpdater`, and re-aligned the desktop config. All four platforms now fetch
the same location (Avery Drive). Two issues consciously deferred (stale default rows; desktop forecast
staleness). **Code changes are uncommitted.**

## Investigation timeline (what the live data showed)

The current temp is recomputed every render as `displayTemp = estimate(now) + delta`, where
`delta = lastObservedTemp − estimate(observedAt)`. Algorithm is shared in `:shared`
(`CurrentTemperatureResolver`, `ActualsAggregator.resolveCurrentObservation`, `TemperatureInterpolator`),
identical for Android and desktop. So divergence had to come from the *inputs*.

### Hypothesis 1 (REJECTED): delta-handling asymmetry
Desktop passes `storedDeltaState = null` (recompute each render); Android passes a persisted
per-widget delta. Theory: between obs fetches they apply different-vintage deltas.
**Disproved by logs** — all four platforms found the observation and applied a ~1.7–2.0° delta
correctly. Captured ~17:2x PT:

| Platform | lat/lon | obsTemp | est(now) | delta | display | stale |
|----------|---------|---------|----------|-------|---------|-------|
| Desktop  | 37.4167,-122.089  | 71.87 | 69.65 | 1.87 | 71.53 | true |
| Emulator | 37.422,-122.0841  | 71.26 | 68.78 | 1.73 | 70.51 | false |
| Pixel    | 37.422,-122.0841  | 71.28 | 68.69 | 2.03 | 70.72 | false |
| Samsung  | 37.422,-122.0841  | 71.27 | 68.69 | 2.03 | 70.71 | false |

The 3 Android devices agreed within 0.2°; desktop was the outlier (+0.8°), explained ~entirely by a
warmer forecast `estimate` (desktop `stale=true` → older NWS revision) **and** a different lat/lon.

### Hypothesis 2 (REJECTED): phones failing GPS, stuck at default
All three Android devices reported the exact default `37.422,-122.0841`, which looked like a GPS
fallback. But the Pixel's **widget prefs** held the real GPS location `37.4168,-122.089` (Avery Drive).
So GPS *had* resolved; the configured location was correct. (Permission was granted: FINE+COARSE.)

### Root cause (CONFIRMED): fetch location decoupled from configured location
Pulled the Pixel DB: **all 25,080 forecast rows at the default** `37.422`; latest row source `Generic`
(GENERIC_GAP climate-normal). The worker resolves the fetch location via
`weatherRepository.getLatestLocation()` =
`forecastDao.getLatestWeather()` (newest row by `batchFetchedAt`) — **never** the widget prefs.
Self-reinforcing loop: latest row's location → fetch that location → store rows there → becomes latest.
Configured location only ever fed rendering (header/sun calc), so a widget could be permanently pinned
to whatever first seeded the table (here, the default).

## What was accomplished

### 1. Worker honors the configured widget location (the real fix)
**File:** `WeatherWidgetWorker.kt` (`doWork`)
- Moved `appWidgetIds`/`stateManager` resolution above location selection.
- New: `val configuredLocation = appWidgetIds.toList().firstNotNullOfOrNull { id -> stateManager.getWidgetLocation(id) }`
- `location = configuredLocation ?: getLatestLocation() ?: (DEFAULT_LAT to DEFAULT_LON)`
- Added `(configured=...)` to the `doWork: Location` log.

**Verified (logcat) — all three devices now fetch Avery Drive:**
- Pixel: `doWork: Location = (37.4168, -122.0889) (configured=true)`, `SYNC_SUCCESS Weather=102`
- Samsung: `(37.41684, -122.089) (configured=true)`
- Emulator: `(37.41684, -122.089) (configured=true)`

**Status:** Complete, verified on device. Unit/instrumented tests NOT yet run.

### 2. ConfigActivity GPS: `lastLocation` → active `getCurrentLocation`
**File:** `ConfigActivity.kt` (`getCurrentLocation`, new `fallBackToLastLocation`)
- `FusedLocationProviderClient.lastLocation` returns only a cached fix (often null after reboot / when
  no app requested location recently), which previously silently saved the default. Switched to
  `getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)`, falling back to
  `lastLocation`, then the hard default.
- Imports: `Priority`, `CancellationTokenSource`.
- **Note:** robustness improvement for *new* widget setups; was NOT the cause of this session's bug
  (the prefs were already correct).

**Status:** Complete, compiles. Not the root cause; keep-or-trim is an open question.

### 3. Foreground GPS auto-heal + shared LocationUpdater
**New file:** `LocationUpdater.kt` — single source of truth for applying a location to all widgets
(`getWidgetIds`, `allWidgetsAtDefault`, `applyToAllWidgets`); mirrors the path Settings always used
(widget prefs + `historical_pois` + force refresh).
**File:** `SettingsActivity.kt` — `saveLocationGlobally` now delegates to `LocationUpdater.applyToAllWidgets`.
**File:** `MainActivity.kt` — `maybeAutoHealLocationFromGps()` called from `onResume` and after a
permission grant: when fine-location is granted AND every widget is still at the hard default, actively
resolve GPS and apply to all widgets. No-ops once a real location is set.
**File:** `strings.xml` — added `location_updated_from_gps`.
- **Note:** safety net for genuinely-default-stuck widgets. Will NOT fire on the user's devices (their
  prefs are correctly Avery Drive).

**Status:** Complete, compiles. Safety net; keep-or-trim is an open question.

### 4. Desktop location re-aligned
- Earlier in session set desktop to the default `37.422` to "match phones"; once we learned the real
  location is Avery Drive, reverted `~/.config/weather-widget/config.json` to `37.4167,-122.089` and
  touched `.config-changed` so the daemon reloaded and restarted fetch loops.

## Final location state — all four aligned (Avery Drive ~37.4168,-122.089)
| Device | Configured | Fetching | Worker fix |
|--------|-----------|----------|-----------|
| Pixel    | Avery Drive | Avery Drive ✓ | installed |
| Samsung  | Avery Drive | Avery Drive ✓ | installed |
| Emulator | Avery Drive | Avery Drive ✓ | installed |
| Desktop  | Avery Drive | Avery Drive ✓ | n/a (config) |

## Deferred / open issues
1. **Stale default rows on phones (cosmetic for now).** Each phone DB still holds default-location
   rows (Pixel: forecasts 25,080; hourly_forecasts 3,592; hourly_forecast_history 39,702;
   observations 6,365; daily_extremes 138). Because Avery Drive and the default are ~0.4 mi apart —
   inside the shared `LocationMatch` ~7 mi proximity box — these still bleed into the render
   (`CURR_TEMP_RESOLVE` still logs `lat=37.422`). Temps are ~identical at 0.4 mi, so deferred. User
   consented to a targeted DELETE (at exactly `37.422,-122.0841`) but we stopped before doing the
   pull→delete→push surgery (no on-device `sqlite3`; requires force-stop + WAL-safe push-back).
2. **Desktop forecast staleness (~1° driver).** Desktop logged `stale=true` — the current-temp window
   reads >2h-old history-stitched rows despite a fresher fetch, so it interpolates an older NWS
   revision than the phones. Investigate `DesktopWeatherDao.getHourlyWithHistory` /
   `DesktopWeatherRepository.resolveForForecastResult`.
3. **Tests.** No automated tests written/run yet. A shared `CurrentTempContract` (à la
   `LocationMatchContract`) run against the resolver + Android + desktop paths was scoped but not built.
4. **Uncommitted.** All code changes above are in the working tree, not committed.

## Lessons learned
- "Same algorithm + same data ⇒ same answer" only fails on "same data." Each platform samples
  independent, time-varying streams (forecast revisions, station obs, wall clock) — pull live logs,
  don't reason from the code. Two hypotheses (delta, GPS) were wrong and only the logs/DB caught it.
- A widget's **configured** location and its **fetched** location were two different things;
  `getLatestLocation()` (latest row) silently won over widget prefs. Watch for "latest row drives the
  next fetch" feedback loops.
- The `LocationMatch` ~7 mi proximity box (good for GPS jitter) means stale rows at a nearby-but-distinct
  point keep matching new-location queries — fixing the fetch isn't enough to immediately change the
  display while old rows persist.
- `IntArray.firstNotNullOfOrNull { it }` didn't resolve; use `.toList().firstNotNullOfOrNull { id -> ... }`.

## Artifacts
- Plan: `~/.claude/plans/you-fixed-this-earlier-polymorphic-pretzel.md` (diagnosis + test plan).
- Diagnostic queries used `CURR_TEMP_RESOLVE` / `CURR_TEMP_RESULT` logs and pulled device DBs
  (`adb exec-out run-as com.weatherwidget cat databases/weather_database`).
