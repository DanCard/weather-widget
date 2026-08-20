# Today-column station drop (again) and stale observations after a location handoff (2026-08-19)

## Report

Samsung (SM-F936U1, widget 345), 2026-08-19 ~19:26 PDT:

1. "dominate station not reporting on daily forecast view, today column" — Today column shows only the
   forecast-delta row; the dominant-station temperature/age rows are absent.
2. "current observations activity displays stale data."

## Evidence (SM-F936U1, database + app_logs)

`TODAY_OVERLAY` before vs after the 18:55:57 location handoff:

```
18:52:44  observedAt=1787188800000 (18:20)  dominantTemp=68°  stationId=KNUQ  dominantNullReason=null
18:57:15  observedAt=1787179620000 (15:47)  dominantTemp=null   stationId=null   dominantNullReason=observed_at_skew(derived=1787190600000 = 18:50)
```

`DOMINANT_STATION` (hourly view, same window) still resolved fresh data:

```
18:56:42  station=KNUQ rawTemp=68.0 readingAgeMin=26 newestObsAgeMin=6
```

`LOCATION_HANDOFF` oscillates ~800 m every 1–2 h (device-following GPS jitter):

```
18:55:57  candidate_promoted location=37.4241668,-122.0884441   (site 37.424)
16:10:56  candidate_promoted location=37.416812,-122.0890163    (site 37.417)
14:59:10  candidate_promoted location=37.424225,-122.0883499    (site 37.424)
14:38:47  candidate_promoted location=37.4176119,-122.0866152   (site 37.418)
```

Observation rows are keyed by fetch site. NWS observations per site on 2026-08-19:

```
37.417 -122.089   latest NWS obs 18:50  (AW020 web), KSJC 18:35, KNUQ 18:30  ← FRESH
37.424 -122.088   latest NWS obs 15:47  (KPAO web)                           ← STALE
```

Forecasts/hourly for the just-finished sync were written at site **37.424** (the promoted
candidate); the current-temperature fetch (`OBS_CURRENT_INSERT station=AW020 timestamp=18:50`, the
separate opportunistic worker) wrote observations at site **37.417** (the then-active location).

## Root cause

1. **Today-column station drop — a LOCATION mismatch, not a window mismatch.** The earlier
   `observed_at_skew` fix (`plans/260819-today-overlay-station-drop-and-dead-opportunistic-loop.md`)
   aligned the *window* and *lookahead*. But the two resolvers still use different *locations*:
   - Producer of `observedAt` — `WidgetRenderer.updateWidgetWithData` uses the **configured widget
     location** (`stateManager.getWidgetLocation`) for
     `CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs` → at 18:57 that was site **37.424**
     → latest NWS obs **15:47**.
   - Today overlay — `DailyViewHandler.updateWidget` derives `lat/lon` from
     `weatherList.firstOrNull()?.locationLat` (the **forecast data location**) → site **37.417** →
     latest NWS obs **18:50**.
   The overlay's exact-equality gate (`TodayColumnOverlayContentResolver.resolveAt`) then drops the
   station rows with `observed_at_skew`, because the two sites are ~770 m apart, beyond
   `LocationMatch.SAME_SITE_TOLERANCE_DEG` (0.002° ≈ 200 m), so `selectNearestObservationSite`
   returns different rows.

2. **Stale observations activity / header** — `WeatherObservationsActivity.resolveLocation()` and the
   widget header both scope to the **configured** location (37.424), whose latest NWS observations are
   from 15:47 (the last time the device was configured there). Fresh observations (18:50) sit at
   site 37.417. After a promotion the new location's observations stay stale until the next
   current-temp fetch (up to 45 min on battery), and the 5-minute `CURRENT_TEMP_FRESHNESS_MS`
   cooldown is **global, not location-scoped**, so an immediate refetch at the new location would be
   skipped.

## Changes

### A. Daily view resolves its location the same way the producer does

`DailyViewHandler.updateWidget` currently derives `lat/lon` from `weatherList.first()?.locationLat`.
Change it to prefer the configured widget location, matching `WidgetRenderer`'s fallback chain, so
the today overlay, the header yesterday-delta, the header delta scope, and the observation query all
resolve against the same location that produced `observedAt`.

### B. Location-scoped current-temperature freshness

`FetchMetadata.lastCurrentTempFetchTime` is a single global key. Make the current-temp fetch time
location-scoped (quantized lat/lon, same pattern as `getLastForecastSourceSuccessTime`), and use the
scoped value in `CurrentTempRepository.refreshCurrentTemperature`, so a location change does not
inherit the previous location's 5-minute cooldown.

### C. Refresh current observations immediately after a location promotion

Centralized in `LocationUpdater.writeActiveLocation` (covers both candidate promotion and manual
location save). After writing the new location, enqueue an opportunistic current-temperature-only
fetch (`CurrentTempUpdateScheduler.enqueueImmediateUpdate`, reason `location_changed`). With B in
place this runs immediately for the new location (battery-gated), instead of waiting up to 45 min
for the next opportunistic slot.

### D. Ignore the battery gate when the user is interacting with the widget

`CurrentTempUpdateScheduler.enqueueImmediateUpdate` gains `userInteraction: Boolean`. The flag is
carried through `WorkInput` to `WeatherWidgetWorker`, where it forces `isManual` (and therefore
`CurrentTempFetchPolicy.shouldFetchNow` returns true regardless of battery).
`RefreshScheduler.refreshIfStale` — the user-interaction stale-refresh path — now enqueues a
current-temperature fetch with `userInteraction=true` alongside the full sync, so the current
observation the user is looking at is refreshed even on low battery.

## Status

- [x] A DailyViewHandler configured-location resolution
- [x] B location-scoped current-temp freshness
- [x] C promotion/manual-location change → immediate current-temp fetch (`writeActiveLocation`)
- [x] D user-interaction current-temp fetch bypasses the battery gate
- [x] tests (`FetchMetadataRoboTest` scoped key; `DailyViewHandlerLocationScopeRoboTest`;
  `CurrentTempUpdateSchedulerTest` user-interaction flag)

Desktop is unaffected by A/B/C/D: `DesktopDailyForecastModel` resolves the overlay via
`resolveLatest` (self-consistent observedAt), and the desktop's fixed `config.json` location does
not experience the GPS-jitter handoff that fragments observation sites on Android.
