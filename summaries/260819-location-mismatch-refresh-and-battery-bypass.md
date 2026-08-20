# Location mismatch refreshes current observations, and battery gate is bypassed on widget interaction (2026-08-19)

## Problem

Samsung (SM-F936U1, widget 345):

1. "dominate station not reporting on daily forecast view, today column" — the Today column showed
   only the forecast-delta row; the dominant-station temperature/age rows were missing.
2. "current observations activity displays stale data."

Follow-up requirements from the user:

3. "When there is a location mismatch, current observation temperature data should be refreshed."
4. "Ignore battery refresh policy if user is interacting with the widget."

## Diagnosis (evidence-first)

`TODAY_OVERLAY` before vs after the 18:55:57 location handoff:

```
18:52:44  observedAt=18:20  dominantTemp=68°  stationId=KNUQ  dominantNullReason=null
18:57:15  observedAt=15:47  dominantTemp=null  stationId=null   dominantNullReason=observed_at_skew(derived=18:50)
```

`LOCATION_HANDOFF` oscillates ~800 m every 1–2 h (device-following GPS jitter):

```
18:55:57  candidate_promoted 37.4241668,-122.0884441  (site 37.424)
16:10:56  candidate_promoted 37.416812,-122.0890163   (site 37.417)
14:59:10  candidate_promoted 37.424225,-122.0883499   (site 37.424)
```

Observation rows are keyed by fetch site. NWS observations on 2026-08-19:

```
37.417 -122.089   latest NWS obs 18:50 (AW020), 18:35 KSJC, 18:30 KNUQ   ← FRESH
37.424 -122.088   latest NWS obs 15:47 (KPAO web)                        ← STALE
```

Forecasts/hourly for the just-finished sync were written at site **37.424** (the promoted
candidate); the current-temperature fetch (a separate opportunistic worker) wrote observations at
site **37.417** (the then-active location).

### Root cause

1. **Today-column station drop is a LOCATION mismatch, not a window mismatch.** The earlier
   `observed_at_skew` fix aligned the *window* and *lookahead*; the remaining divergence was the
   *location*:
   - Producer of `observedAt` (`WidgetRenderer`) resolves against the **configured widget location**
     (37.424 → stale 15:47).
   - Today overlay (`DailyViewHandler`) resolved against the **forecast data location**
     (`weatherList.first().locationLat` = 37.417 → fresh 18:50).
   The overlay's exact-equality gate dropped the station rows. The two sites are ~770 m apart,
   beyond `LocationMatch.SAME_SITE_TOLERANCE_DEG` (0.002° ≈ 200 m), so `selectNearestObservationSite`
   returned different rows.
2. **Stale observations** — the observations activity and the widget header both scope to the
   configured location (37.424), whose latest NWS reading was 15:47. Fresh rows were at 37.417, and
   the 5-minute `CURRENT_TEMP_FRESHNESS_MS` cooldown was **global**, not location-scoped, so an
   immediate refetch at the new site would have been skipped.

## Changes

### A. Daily view resolves its location the same way the producer does

`DailyViewHandler.updateWidget` now prefers `stateManager.getWidgetLocation(appWidgetId)` over
`weatherList.first().locationLat`, matching `WidgetRenderer`'s fallback chain. The today overlay,
header yesterday-delta, header delta scope, and the observation query all resolve against the same
location that produced `observedAt`.

### B. Location-scoped current-temperature freshness

`FetchMetadata` gained `get/setLastCurrentTempFetchTime(context, lat, lon)` (quantized site key,
same pattern as the forecast source success key). `CurrentTempRepository.refreshCurrentTemperature`
gates the 5-minute cooldown on this per-site value, so a location handoff no longer inherits the
previous site's cooldown.

### C. Location change → immediate current-observation refresh

Centralized in `LocationUpdater.writeActiveLocation` — the single point every location write goes
through — so it fires for both GPS-handoff promotions and manual location saves. It enqueues
`CurrentTempUpdateScheduler.enqueueImmediateUpdate(reason = "location_changed", opportunistic = true)`.

### D. Ignore battery policy when the user is interacting with the widget

- `CurrentTempUpdateScheduler.enqueueImmediateUpdate(...)` gained `userInteraction: Boolean`.
- `WorkInput` and `WeatherWidgetWorker` carry `KEY_USER_INTERACTION`; the worker's `isManual` now
  includes `input.userInteraction`, so `CurrentTempFetchPolicy.shouldFetchNow` returns true
  regardless of battery.
- `RefreshScheduler.refreshIfStale` (the user-interaction stale path — tap, nav, view toggle, day
  click, resize) now enqueues a current-temperature fetch with `userInteraction=true` alongside the
  full sync (the full sync fetches weather/hourly but never current observations).

## Verification

- New tests: `FetchMetadataRoboTest` (scoped freshness key), `DailyViewHandlerLocationScopeRoboTest`
  (observation query uses the configured location), `CurrentTempUpdateSchedulerTest` (user-interaction
  flag passed to worker).
- Green suites: `CurrentTempUpdateSchedulerTest`, `LocationUpdaterTest`, `CurrentTempFetchPolicyTest`,
  `WidgetIntentRouterExecutionTest`, `DailyViewHandlerTest`, `LocationHandoffRoboTest`,
  `DailyViewHandlerTodayDropIntegrationTest`, shared `TodayColumnOverlayObservedAtSkewTest`.
- Installed the debug APK on SM-F936U1 and refreshed; device returned fresh data and the dominant
  station reporting normally (`source=NWS observedAt=19:47`, `DOMINANT_STATION station=KNUQ
  newestObsAgeMin=0`).

## Notes

- The background location-change refresh (C) remains battery-gated by design; only the
  user-interaction path (D) bypasses the gate.
- The configured location keeps jittering `37.417 ↔ 37.424` every 1–2 h (device-following GPS), so
  the site-fragmentation root cause remains: each handoff splits observations across sites. The
  broader site-collapse tolerance / promotion-criteria question is left as a follow-up.
- Desktop is unaffected: `DesktopDailyForecastModel` resolves the overlay via `resolveLatest`
  (self-consistent observedAt), and its fixed `config.json` location does not experience the
  GPS-jitter handoff.
