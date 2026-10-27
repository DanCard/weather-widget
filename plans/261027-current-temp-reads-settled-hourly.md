# Current-temperature estimate reads the settled hourly on both platforms

**User, 2026-10-27:** "fix the current temperature resolver follow-up"

Follow-up to [plans/261027-pre-hour-freeze-retention-and-shared-pick.md](261027-pre-hour-freeze-retention-and-shared-pick.md).

## Evidence (code trace)

`CurrentTemperatureResolver` estimates "now" from the current and next hour, then adds the
observation's error at its own timestamp. The two platforms feed it different rows:

- **Desktop**: `DesktopWeatherRepository.loadCached` reads `getHourlyWithHistory`, which is stitched.
  Since a2f157d8 the current (started) hour carries its **pre-hour** forecast.
- **Android**: `GraphDataLoader.loadCurrentTempInputHourlyForecasts` reads `hourly_forecasts` only.
  The current hour carries the **latest fetch**, a hindcast once the hour has started.

`pickBestForecast` takes `maxBy(fetchedAt)`. That is a no-op on stitched rows and the latest row on
live rows. So for the same data the headers can differ across platforms, and Android's header leaves
its own graph line, which is stitched.

## Fix

1. **`:shared` `CurrentTemperatureResolver.pickBestForecast`** uses `SettledForecastPick.pick(rows,
   hour, nowMs)` instead of `maxBy(fetchedAt)`. Every caller, raw rows or stitched, then gets the
   graph's rule. `nowMs` is threaded from `resolve` / `resolveQuick` / `computeSmoothedForecasts`,
   which already take a now.
2. **`:app` `GraphDataLoader.loadCurrentTempInputHourlyForecasts`** stitches live + history over the
   current-temp input window (`getHistoryInRangeForBucketWindowAllSources` → `stitchBySource`), so
   the window's elapsed hours exist even after the live table moved on. It now matches desktop's
   stitched read.

Unchanged: the newest-run guard (`newestRunIfObservationBracketIsOlder`, the OWM 100°F fix). It
still swaps to the newest run only when that run explains the observation better.

## Behaviour change

The estimate for the current hour uses its pre-hour forecast. The anchor delta absorbs the level, so
the header moves only by the change in forecast *shape* between the observation time and now,
typically a few tenths of a degree. Desktop already behaves this way.

## Tests

- Resolver (`:shared`): raw rows with a started current hour forecast at 60 (pre-hour) and 70
  (post-hour), and the next hour at 64. The estimate interpolates 60→64. Must fail on df9867b3.
- Android (Robolectric, real Room): with a history pre-hour snapshot and a post-hour live row for
  the current hour, `loadCurrentTempInputHourlyForecasts` returns the pre-hour value. Must fail on
  df9867b3.
- Full gate + parity; rebuild/restart desktop; `installDebug`; compare `CURR_TEMP_RESULT` estimate
  on desktop vs a phone at the same site.
