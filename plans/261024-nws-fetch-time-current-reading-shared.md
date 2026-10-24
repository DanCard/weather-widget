# NWS fetch-time current reading: one shared rule (finishes audit item E)

User, 2026-10-24: "go ahead with step 1" — the last E twin from
`261023-duplicate-logic-audit-different-names.md` ("Header blend still per platform
(`interpolateIDW` vs `NwsBlend.build`) — item E").

## What is and isn't twinned
- **The displayed `NWS_BLEND` row is already shared.** Both platforms compute it on read with
  `:shared` `NwsBlend.current` (Android `CurrentObservationReader`, desktop
  `DesktopWeatherRepository:270`); it is never stored. Nothing to do there.
- **The twin is the fetch-time reading** an NWS observation cycle returns — "temperature, condition,
  observed-at now" from the stations just fetched:
  - Android `NwsCurrentObservationUpdater.fetchNwsCurrent` → `SpatialInterpolator.interpolateIDW`
    on each station's latest reading, its own nearest-station condition, `NWS_IDW` log.
  - Desktop `DesktopWeatherService.fetchNwsObservations` and `fetchNwsObservationsOnly` →
    `NwsBlend.build`, plus fallbacks, no log.
- Where it goes: `CurrentConditionsRun.Reading` (success/failure accounting and the
  `CURRENT_TEMP*` log lines) on both; on desktop also `RawFetch.providerCurrentTemp`, which
  `rawFetchToSnapshot` shows as the header for the moment between a fetch and the cache read.

## Divergences
1. **QC-failed readings.** `NwsObservationPlanner.mergeLatest` keeps the API's latest reading when
   the web reading is not used, even if it failed QC. The temperature is the same on both
   (`interpolateIDW` drops QC-failed readings itself — corrected after approval), but Android takes
   the **condition** (nearest station) and **observed-at** (newest timestamp) from every latest
   reading, QC-failed included; `NwsBlend.build` only from usable ones.
2. **Nothing blendable** (every latest reading > 3 h old, or all QC-failed):
   - Android: no reading → the run logs `success=false` (WARN).
   - Desktop `fetchNwsObservations`: the hourly forecast interpolated, or the first hourly temp.
     `fetchNwsObservationsOnly` (the path the run uses): the nearest station's latest temperature,
     however stale. Then the run's Host falls back again to the newest raw row. Either way it reports
     success, and the snapshot header shows the stale value as the current temperature.
   `NwsBlend`'s own KDoc calls these "a forecast stored as an observation" and keeps them out of the
   table, but the header fallback is the raw-row fallback the user dropped on both platforms on
   10-24 (`261024-shared-current-temp-run.md`): with no blended reading the header is the bare
   forecast.
3. **Condition**: Android takes the nearest station among those with a latest reading; desktop the
   nearest station bundle even when its latest is null (condition null), then the hourly forecast's
   `shortForecast`. `NwsBlend.build` takes the nearest **usable** reading.
4. **Log**: `NWS_IDW` on Android only.

## Plan
1. `:shared` `NwsBlend.fetchReading(latestReadings, lat, lon, weight, nowMs)` →
   `(temperature, condition, observedAt)` or null, built on `NwsBlend.build` (QC filter, nearest
   usable condition, newest usable timestamp), plus `NwsBlend.idwLine(...)` for the `NWS_IDW` line
   (stations, stored count, ms).
2. Android `fetchNwsCurrent`: `CurrentReadingPayload` from `fetchReading`; logs the shared line.
3. Desktop `fetchNwsObservations` / `fetchNwsObservationsOnly`: `providerCurrentTemp`,
   `providerCurrentCondition`, `providerCurrentObservedAt` from `fetchReading`; log `NWS_IDW` to
   app_logs. **Decision needed (below)** on the fallbacks.
4. Desktop run Host (`DesktopWeatherRepository:1071`): no newest-raw-row fallback for NWS. A
   station network with no blendable reading is "no reading", as on Android.

## Decision (user, 2026-10-24: yes)
Drop desktop's no-blend fallbacks (forecast temp; stale nearest station; newest raw row)?
**Recommended: yes.** They show a forecast or a > 3 h-old reading as the current temperature, which
the 10-24 decision ruled out. With them gone, desktop's between-fetch-and-cache header for NWS comes
from `resolveForForecastResult(raw.hourly, raw.rawObservations)` (the `CurrentTempRun` rule, as
`resolveCurrentTempInMemory` already does): the blend if there is one, else the bare forecast.
The run would then log `success=false` for an NWS cycle with nothing blendable, as Android does.

## Tests
- `:shared` `NwsBlendFetchReadingTest`: a QC-failed nearest station gives neither the condition nor
  the observed-at (Android's old code took both from it); every reading > 3 h old → null.
- Android: `fetchNwsCurrent` with a QC-failed nearest station returns the usable station's
  condition and time.
- Desktop: `fetchNwsObservationsOnly` with only stale readings → `providerCurrentTemp == null` (was the
  stale station's temperature); the run Host returns no reading.
- `CrossPlatformDuplicateLogTagsTest`: `NWS_IDW` defined in `:shared`.
- Full suite; install on both phones (`NWS_IDW` in logcat); desktop `NWS_IDW` in app_logs; restart
  desktop.

## Done (2026-10-24)
- `:shared` `NwsBlend.fetchReading` (on `build`) + `NwsBlend.idwLine` / `IDW_TAG`.
- Android `fetchNwsCurrent` uses them; `NWS_IDW` is now also logged when nothing blends
  (`blended=none`).
- Desktop `fetchNwsObservations` / `fetchNwsObservationsOnly` use them and log `NWS_IDW` to app_logs.
  Removed: the forecast fallback (and `storedNwsHourlyPeriods`, its only feed), the stale
  nearest-station fallback, the `shortForecast` condition fallback, and the run Host's
  newest-raw-row fallback. `rawFetchToSnapshot` (between fetch and cache read) resolves with
  `resolveForForecastResult`, the cached path's rule, for every source, not the provider-current
  field.
- Tests: `NwsBlendTest` (QC-failed nearest station gives neither condition nor time; nothing
  blends → null), Android `NwsCurrentFetchReadingTest` (same through the updater and Room; asserts
  the QC-failed row was stored, so the old rule would have used it), `DesktopNwsBlendStaleTest`
  (stale-only → no current reading; was the 5 h-old station). Full suite green.
