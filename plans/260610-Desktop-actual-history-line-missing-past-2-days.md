# Plan: Desktop actual-history line missing past ~2 days

## Context

After adding continuous scroll-zoom (up to 6 days back) to the desktop temperature/precip/cloud
graphs, the **forecast line** now extends the full 6 days but the **actual (observed) line stops
at ~2 days back**. Scrolling further out shows the predicted curve over empty actuals.

Root cause: `DesktopWeatherRepository.loadCached()` queries the `observations` table for only the
**past 48 hours** before handing them to the graph (`snapshot.rawObservations` →
`ActualTemperatureSeriesBuilder.build(observations = …)`). The previous zoom change widened the
*hourly* read and the *actuals-context lookback* to 144h, but this sibling observation query three
lines below was missed, so the actual line is capped at 48h regardless of zoom.

Verified the data is present (not a fetch/retention problem):
- NWS observations are fetched 7 days back every refresh (`DesktopWeatherService.HISTORY_DAYS = 7L`)
  and retained 30 days (`weatherDao.cleanup(now - 30 days)`).
- Live DB (`~/.local/share/weather-widget/weather.db`) holds ~14 days of observations
  (600–900 rows/day for the last 8 days; oldest ≈338h back). Only the read window is too small.

The actual line is built strictly from `observations`; the builder already honors `backHours` and
`contextLookbackHours` (both 144h now), so widening the observation read is the entire fix. It
applies to all three hourly graphs (they all receive `snapshot.rawObservations`).

## Change

`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt` — in `loadCached()`
(~line 82-83), widen the observation read to match the widest zoom-out, reusing the same constant
already used for the hourly read in this file:

```kotlin
// Cover the widest zoom-out (6 days back) so the actual line spans the whole window.
val obsStart = now - (DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L)
val obsEnd = now + (2 * 3600 * 1000L) // unchanged cushion
```

(replacing `now - (48 * 3600 * 1000L)`). No other code changes: the builder, fetch depth, retention,
and the freshness gate (`newestObs`/`latestObs`, which only governs the current-condition dot) are
all unaffected.

## Verification

1. Build + restart: `scripts/build-exe-and-restart.sh` (project convention — rebuilds, stops, relaunches).
2. Open the temperature graph and scroll-wheel out toward the 6-day view. Confirm the **actual line
   now extends across the full window** (no longer truncating at ~2 days), and switch to
   precipitation/cloud to confirm they also show their full-span actual data.
3. Data sanity (already confirmed, repeatable): observations exist well past 6 days —
   `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT (strftime('%s','now')*1000 - MIN(timestamp))/3600000.0 FROM observations;"`
   returns ~338 (hours).
4. Regression: `./gradlew :desktop:test` (loadCached is exercised by repository/integration tests).
