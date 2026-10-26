# Forecast and observation cadence

Forecast freshness, Android worker wakeups, current observations, and UI repaints are separate
schedules. The shared policies below apply to Android and desktop; platform schedulers only decide
when to ask them for work.

## Forecasts: every fetch is LONG

`shared/src/main/kotlin/com/weatherwidget/data/remote/ForecastHorizonCadence.kt` is the source of
truth. Daily and hourly products are fetched together for every eligible source. Every fetch asks
for the source's full supported horizon; there is no shorter three-day forecast fetch.

| Power | Screen | Fetch interval |
|---|---|---|
| Charging or battery ≥ 70% | On | 4 hours |
| Charging or battery ≥ 70% | Off | 8 hours |
| Battery < 70% | On or off | 24 hours |

- A fetch stores every returned daily row and up to eight days of live hourly. History snapshots
  retain the routine 72-hour horizon. Daily cloud/precipitation summaries are computed from the whole
  download before live-hourly trimming.
- Freshness uses one per-source, per-site LONG marker, not a forecast row's `fetchedAt`.
- Manual Refresh, source switches, location changes, and screen-on catch-up also use LONG when their
  own policy requests a forecast.
- On a charger, pre-wake policy can move an overnight fetch to shortly before the next alarm instead
  of fetching twice while nobody is looking.
- `SourceFetchGate` excludes sources not viewed recently from routine background work. Targeted user
  actions and on-demand hourly requests are separate from this background gate.

The previous LONG/SHORT design is documented only in dated performance investigations; it is not the
current behavior.

## Android worker tick

`ForecastFetchPolicy.periodicTickMinutes` controls when Android wakes to ask which sources are due
and to resample location. It is not itself the forecast interval.

| Power | Periodic tick |
|---|---|
| Charging or battery ≥ 80% | 60 minutes |
| Battery 70–79% | 240 minutes |
| Battery 50–69% | 480 minutes |
| Battery < 50% | 24 hours |

Desktop uses `ForecastSchedule` and `DesktopForecastPlanner` to sleep until the next due fetch rather
than using the Android periodic worker. Power, presence, network restoration, and retry policy can
wake or defer either platform's scheduler without changing the freshness rules above.

## Current observations and temperature

`shared/src/main/kotlin/com/weatherwidget/shared/util/CurrentTempCadence.kt` governs the displayed
source's current-temperature/recent-observation fetches:

| Power | Screen on | Screen off |
|---|---|---|
| Charging | 10 minutes | 30 minutes |
| Battery ≥ 70% | 20 minutes | 45 minutes |
| Battery 66–69% | 45 minutes | None |
| Battery ≤ 65% | None | None |

Android uses charging WorkManager, battery screen-on alarms, and an opportunistic JobScheduler band;
desktop uses its observation loop. Screen-on catch-up fetches only when the relevant observation age
and battery policy permit it. Other enabled sources follow `NonPrimaryObservationPolicy`, not the
displayed source's table.

A forecast fetch never carries station observations or a current reading: NWS's is the forecast only
on both platforms (`NwsForecastReply`). Desktop's full refresh adds the shared
`ActualsFeedPolicy.fullRefreshFetch` observation pull for the displayed source's feed — NWS displaying
itself included — taking the recent window when stored rows reach the last hour and the week only on a
real gap.

Recent NWS station history is filled by the shared `NwsGapFillRun` daily pass — Android's full sync,
desktop's launch/wake catch-up after it publishes. It runs only when day−2, yesterday or (from 02:00)
today has no NWS `daily_history` row, a past day's NWS rows stop before 14:00
(`pastDayLacksAfternoonCoverage`), or a day's official-station rows leave a hole of over 3 h, today
counted up to now (`dayHasOfficialGap`, so a wake after a night asleep refills the night even though
personal stations covered it through the Synoptic fallback); then it asks up to five stations, nearest first, for the last
3 days and stops once every needed day is complete. A complete week makes no request. Logs:
`NWS_DAILY_BACKFILL_*` on both platforms, plus desktop's `OBS_WINDOW_REFRESH need=…` summary.
Android's render-detected observation gap runs the same runner's recent pass (every station, the last
72 h, `OBS_HOURLY_BACKFILL_*`). A fill that never reached NWS — station lookup failed or every station
failed, not an answered-but-empty one — retries on the shared `ObservationBackfillRetryPolicy`
(10 s → 1 m → 4 m): Android as delayed worker requests, desktop as coroutine delays after the wake,
stopping if the location or source changes (`OBS_HOURLY_BACKFILL_RETRY` on both).

Interpolating a current temperature from cached hourly rows is a UI operation, not a network fetch.
Repaints can therefore be more frequent than observation requests without changing provider usage.

## Targeted and early refreshes

A day tap or graph navigation can request missing hourly coverage through `HourlyOnDemand` rather
than forcing every source to sync. Only this site's stored rows (`LocationMatch.sameSite`) count as
covering a day or as a recent fetch, on both platforms — the view shows the site's rows, so a nearby
town's rows in the read box must not skip a fetch. User Refresh, source switches, history-specific refills, and
location changes have their own targeted scope. These paths do not make routine forecast cadence
equivalent to "fetch everything on every interaction."

Android screen-on receivers registered at runtime work only while the process is alive. Manifest
`USER_PRESENT` delivery has measured unreliable, and manifest `ACTION_SCREEN_ON` registration is not
a substitute for the runtime receiver.

## Related code

- Forecast cadence: `ForecastHorizonCadence.kt`
- Forecast worker tick: `ForecastFetchPolicy.kt` and `BatteryTier.kt`
- Observation cadence: `CurrentTempCadence.kt` and `NonPrimaryObservationPolicy.kt`
- Targeted hourly coverage: `HourlyOnDemand.kt` and `HourlyOnDemandRun.kt`
- Snapshot timing: `HourlySnapshotRun.kt`
- Desktop planning: `ForecastSchedule.kt` and `DesktopForecastPlanner.kt`
