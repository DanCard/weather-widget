# Transient "No Cloud Data" From a Stale Hourly Source-Scope Snapshot

**Date:** August 8, 2026
**Device:** Samsung Galaxy Z Fold (SM-F936U1), widget 345, cloud cover view, Tomorrow.io source
**Status:** Implemented, unit-tested, installed and running clean on-device (uncommitted)

---

## 1. Problem

Toggling the API indicator to Tomorrow.io rendered "no cloud data", then healed on its own a few
seconds later. Not an API failure and not coordinate fragmentation.

Captured live from the device — a 2.5-second window:

```
19:14:37.7  HourlyForecastLoader: load: stitched=466      <- SQL scoped while source = SILURIAN
19:14:38.5  GraphInteraction: render ... source=TOMORROW_IO   <- user toggle
19:14:40.2  worker_paint_start -> CloudCoverViewHandler hourlyCount=0 source=TOMORROW_IO
            CLOUD_COVER_GAPS missing=24 total=24 -> CLOUD_COVER_GAPS_REFRESH (forced sync)
19:15:06    next paint hourlyCount=169 -> healed
```

`HourlyForecastLoader.hourlySourceIds()` scopes the hourly SQL to whatever every installed widget is
displaying **at that moment** — a deliberate perf fix (unfiltered it pulled ~38k rows on the Fold,
the `hourly=1888ms` SYNC_PERF stage). But the worker calls it *before* `getWeatherData()`, and the
repaint re-reads the display source *after* the fetch. A toggle inside that gap leaves a row set that
physically cannot contain the new source, so `WidgetRenderer.sourceFilteredHourly` filtered 466 rows
down to 0.

**The tell:** `WidgetRenderer` logged `hourlyCount=466` in the same pass where the handler logged
`hourlyCount=0` — the rows existed, just none for the display source. The DB confirmed this was not
fragmentation: widget 345 sits at 37.416817/-122.08903, and that site holds 816 Tomorrow.io rows.

The secondary cost was worse than the blank frame. The gap detector saw 24/24 hours missing and fired
`enqueueRedundantImmediateSync(forceRefresh = true)` — a real API round-trip spent on a bookkeeping
mismatch, which itself restarts a worker and re-opens the same window.

## 2. What Changed

### 2.1 The worker reloads instead of painting stale rows — `WeatherWidgetWorker.kt`

The source scope is captured as `hourlySourceIdsAtLoad`, and before the actuals recompute the worker
asks `HourlyForecastLoader.sourcesMissingFromLoad(loaded, atPaint)`. If a source appeared during the
fetch, it re-runs one scoped query and uses that list.

The reload sits **before** `fetchDailyActuals`, not immediately before the paint.
`fetchDailyActuals` already reads `currentDisplaySourceIds()` fresh while consuming the stale rows,
so it had the same race one stage earlier. Reloading at the earlier point fixes the actuals, the
paint, and the `reloadActuals` escape-hatch lambda together.

### 2.2 The gap detectors no longer burn a forced sync on a bookkeeping miss

`WidgetRenderer` computes `sourceMissingFromLoad` (rows present, but none for the display source) and
passes it to the handlers, which log it and skip `enqueueRedundantImmediateSync`.

Two findings changed the shape of this half:

- **All three views had the bug**, not just cloud cover. `PrecipViewHandler` and
  `TemperatureStateResolver.loadGraphHours` run the identical detector on the identical input, so a
  toggle would fire a spurious sync from whichever view was on screen. All three are fixed.
- **A handler cannot derive the flag itself.** The first attempt used
  `sourceRows == 0 && hourlyForecasts.isNotEmpty()` inside `CloudCoverViewHandler`, but a handler
  only receives `sourceFilteredHourly` — when the source is missing that list is *empty*, so the
  guard could never fire. More fundamentally, the filter destroys the information needed to tell
  "the loader never asked for this source" from "the API genuinely has nothing". Only
  `WidgetRenderer` sees the unified list, so the flag must be threaded from there.

### 2.3 Logging

- `HourlyForecastLoader` `load:` line now carries `sources=NWS|Generic|…`, the actual SQL scope.
  Previously a row count told you nothing about which sources it covered.
- `HOURLY_SOURCE_MISS` (WARN, `WidgetRenderer`) — fires when the filtered list is empty but the
  unified list is not. Carries `displaySource`, `present=<source:count,…>`, `origin`, `site`.
  `present` is a per-source histogram on purpose: zero rows for your source alongside 466 rows of
  others is this race; zero across the board is a location or fetch problem.
- `HOURLY_SOURCE_SNAPSHOT_STALE` (WARN, `WeatherWidgetWorker`) — compares the load-time snapshot
  against the paint-time source list and names which source went missing, with
  `staleRows`/`reloadedRows`.

## 3. Files Touched

| File | Change |
|---|---|
| `WeatherWidgetWorker.kt` | Snapshot scope, detect drift, reload before actuals + paint |
| `HourlyForecastLoader.kt` | `sourcesMissingFromLoad()` pure fn; `sources=` in the load log |
| `WidgetRenderer.kt` | `sourceMissingFromLoad` + `HOURLY_SOURCE_MISS`; passed to 3 handlers |
| `CloudCoverViewHandler.kt` | Accept flag, guard the redundant sync |
| `PrecipViewHandler.kt` | Same guard |
| `TemperatureViewHandler.kt` / `TemperatureStateResolver.kt` | Thread flag through to `loadGraphHours`, same guard |

## 4. Testing

`HourlyForecastLoaderSourceScopeTest` — 4 cases against the extracted pure function (this repo has no
mocking framework; the decision was extracted rather than mocked). Verified the tests *can* fail by
inverting `filterNot` → 6 failures, then restored.

The polarity trap is covered explicitly: a source dropping off screen must **not** trigger a reload,
since the loaded set is already a superset and reloading would spend a query to change nothing.

Existing `com.weatherwidget.widget.*` and `handlers.*` suites pass. Installed on 4 devices; the Fold
runs clean post-install (`load: stitched=227 ... sources=NWS|Generic`, no crashes).

## 5. Notes

The source-scoped SQL is deliberate and must stay — unfiltered it returned ~38k rows on the Fold. The
bug was the stale snapshot, not the filtering.

The two fixes are independent by design: the reload prevents the blank frame, the guard prevents the
wasted API call. Both are needed because the reload closes the *worker's* window, but a toggle can
still land between `WidgetRenderer` reading prefs and the handler rendering — the guard makes that
residual case cheap instead of expensive.
