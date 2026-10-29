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
- On a charger, a forecast also comes due 45 minutes before the device is predicted to leave the
  charger (see [pre-unplug fetch](#pre-unplug-fetch)), and the overnight fetch that would be overwritten
  first is skipped.
- On a charger, the screen coming on fetches a displayed source's forecast when the 4-hour screen-on
  interval makes it due (`ForecastHorizonCadence.screenOnCatchUp`; Android `ScreenOnReceiver`,
  `SCREEN_ON_FORECAST`). The desktop forecast loop re-plans on every screen wake, which has the same
  effect.
- `SourceFetchGate` excludes sources not viewed recently from routine background work. Targeted user
  actions and on-demand hourly requests are separate from this background gate.

The previous LONG/SHORT design is documented only in dated performance investigations; it is not the
current behavior.

## Pre-unplug fetch

`shared/src/main/kotlin/com/weatherwidget/shared/util/PreUnplugFetchRunner.kt` owns the rule on both
platforms; each platform supplies a host (Android prefs + WorkManager + the clock app's alarm, desktop
file + forecast-loop wake, no alarms). A fetch ahead of an unplug is the only one a charger saves the
battery; a device that stays plugged in fetches at screen-on instead.

- `ChargeHistory` records every charge session of at least 15 minutes (exact power event, else the last
  charging sighting at most 45 minutes earlier) and every fired clock alarm, for 8 weeks.
- `UnplugPredictor` clusters unplug times of day (30 minutes apart at most) and judges each cluster's
  window by recency-weighted hit rate (half-life 3 weeks). A day is a hit when an unplug fell in the
  window and a miss when the device was charging at its start and stayed so; days off the charger count
  as neither.

| Evidence | Lookback | Active when |
|---|---|---|
| Weekly (same weekday) | 8 weeks | ≥ 2 hits and rate ≥ 0.5 |
| Daily (weekdays or weekend days) | 21 days | ≥ 4 hits and rate ≥ 0.5 |
| Clock alarm (±30 min of its time) | 8 weeks | Beta(1, 1) mean of "unplugged within 90 min" > 0.5 |

- The prediction is the earliest active candidate within 14 hours: a pattern's earliest observed unplug,
  or a trusted alarm plus its earliest learned follow. A bare alarm with no history predicts nothing.
- The check is armed 40 minutes before it (45-minute lead, 5 minutes of slack); the cadence fetches only
  if the last fetch is at least 3 hours older than the predicted unplug.
- Logs: `UNPLUG_RECORDED`, `UNPLUG_PATTERNS` (daily), `PRE_UNPLUG_FETCH` (armed/cancelled; Android also
  `action=alarm_changed` per alarm broadcast), `PRE_UNPLUG_OUTCOME` (hit with minutes early/late, miss,
  unknown). The sync reason is `pre_unplug`.

## Background wakes (both platforms)

Both platforms wake when the next fetch is due, from the shared `ForecastSchedule`: the earliest
`ForecastHorizonCadence` due time across the background sources (including the pre-unplug lead),
floored at 1 minute, 15 minutes after a failed fetch, and capped at 24 hours (`MAX_SLEEP_MS`, the longest
forecast interval — a backstop for a planner bug, not a poll).

- **Desktop:** the forecast loop sleeps on it (`FORECAST_LOOP_SLEEP`). Power-tier changes (UPower, or
  the daemon's 5-minute charger sighting if the monitor is dead), screen wakes, resume and network
  restore wake it sooner.
- **Android:** every full sync enqueues one delayed `due_wake` sync at the planned wake
  (`WidgetWorkScheduler.enqueueDueWakeSync`, logged `FORECAST_DUE_WAKE`; append rather than replace
  while that work is running). With none pending (after an install or upgrade), a current-temp run plans
  one (`trigger=missing`). The periodic worker is a 24-hour backstop (`periodic_1440m`). A battery
  screen-on only brings the due-time sync forward to the 4-hour screen-on due time
  (`SCREEN_ON_FORECAST outcome=replanned`); a charging screen-on fetches when due.
- Duties that ride a full sync (NWS gap fill, daily-history upkeep, snapshot prune) are once-a-day or
  idempotent and run on the next fetch; the battery location floor stays in the 45-minute
  opportunistic job, which still runs when it no longer fetches.

## Current observations and temperature

`shared/src/main/kotlin/com/weatherwidget/shared/util/CurrentTempCadence.kt` governs the displayed
source's current-temperature/recent-observation fetches:

| Power | Screen on | Screen off | Screen off, dominant-station watch armed |
|---|---|---|---|
| Charging | 10 minutes | 60 minutes | 30 minutes |
| Battery ≥ 70% | 20 minutes | None | 45 minutes |
| Battery 66–69% | 45 minutes | None | None |
| Battery ≤ 65% | None | None | None |

Nobody sees a screen-off reading: a screen-on fetches when the last one is old enough (charging ≥ 15
minutes, battery ≥ 20 minutes), and history is refilled (NWS gap fill; every forecast fetch takes
Tomorrow.io's last 23 hours). The armed one-shot notification is the exception. The opportunistic
45-minute band never fetches on a charger (the loop covers it).

An unplug the app did not hear is placed at the last charging sighting if it is within the screen-off
charging interval plus 15 minutes (`ChargeHistory.MAX_ESTIMATE_GAP_MS`); a screen-on is a sighting too.

Android uses charging WorkManager, battery screen-on alarms, and an opportunistic JobScheduler band;
desktop uses its observation loop. Screen-on catch-up fetches only when the relevant observation age
and battery policy permit it. Other enabled sources follow `NonPrimaryObservationPolicy`, not the
displayed source's table.

A forecast fetch never carries station observations or a current reading: NWS's is the forecast only
on both platforms (`NwsForecastReply`). Desktop's full refresh adds the shared
`ActualsFeedPolicy.fullRefreshFetch` observation pull for the displayed source's feed — NWS displaying
itself included — taking the recent window when stored rows reach the last hour and the week only on a
real gap.

Recent NWS station history is filled by the shared `NwsGapFillRun` daily pass. Every trigger enters
through `NwsGapFillRun.dailyForSources` — Android's full sync, desktop's launch/wake catch-up after it
publishes, and a user Refresh on both platforms (desktop Settings → Refresh after its fetch; Android's
widget Refresh on fresh data enqueues it alone as `WORK_NAME_HISTORY_GAP_FILL`, since a stale tap's
sync runs it already). That entry owns the source gate: it runs when any visible source takes its
actuals from NWS (`ActualsFeedPolicy.requiresFeed`), not only the displayed one. The pass itself runs
only when day−2, yesterday or (from 02:00) today has no NWS `daily_history` row, a past day's NWS rows
stop before 14:00 (`pastDayLacksAfternoonCoverage`), or a day's official-station rows leave a hole of
over 75 min (one hourly METAR cycle plus a late report), today counted up to now (`dayHasOfficialGap`,
so a wake after a night asleep refills the night even though personal stations covered it through the
Synoptic fallback and the wake fetch stored a few stray official rows); then it asks up to five
stations, nearest first, for the last 3 days and stops once every needed day is complete. A complete
week makes no request. Logs, identical on both platforms: one `NWS_DAILY_BACKFILL_RESULT
reason=… need=… rows=…` line per run plus the per-station `NWS_DAILY_BACKFILL_*` lines.
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

### User-initiated refresh throttle (10-minute limit)

User-initiated manual refresh triggers (Android widget tap-to-refresh, Settings "Refresh data",
Forecast History refresh, Observations refresh, and Desktop UI refresh buttons) bypass routine
background cadence checks so that an explicit user tap can fetch immediately. However, manual user
refreshes are limited across both platforms to at most once every 10 minutes per weather source
(and observations scope) via `UserRefreshThrottle`:

- **Throttled (< 10 minutes since last user refresh of that source)**:
  - Network syncs and gap fills for that source are suppressed.
  - A user-visible notice is shown (Android home widget message banner, in-app `Toast.LENGTH_LONG`
    on Settings/History/Observations, or Compose `RefreshNoticePill` on Desktop):
    `"Not doing because last refresh was X minute(s) ago, and wait another Y minute(s) before asking for a refresh"`.
  - Logged with `USER_REFRESH_THROTTLED source=… elapsedMin=… waitMin=…` and `REFRESH_TOAST … none=RATE_LIMITED source=…`.
- **Allowed (≥ 10 minutes or first user refresh for that source)**:
  - Last user refresh timestamp for that source is recorded (`FetchMetadata` per source on Android, `lastUserRefreshMsBySource` on Desktop).
  - Immediate forced forecast sync and history gap fill are enqueued.
  - A user-visible notice indicates that the refresh is queued.
- **Passive events are exempt**:
  - Background periodic worker ticks, power connect/disconnect events, screen unlock repaints
    (`EXTRA_PASSIVE = true`), and UI-only ticks do not check, update, or get blocked by this throttle.

### Multi-widget render prioritization and interaction preemption

On devices with multiple home-screen widgets (or foldables like Galaxy Z Fold across cover and inner displays), widget updates avoid competing with the user's active view:

- **Priority ordering (`WidgetPaintPrioritizer`)**: Batch repaints (worker sync completion, screen-on catch-up, cache refreshes) render the most-recently-interacted widget at index 0. Remaining widgets are ordered by form factor relevance (e.g. inner vs. cover display on foldables matching `smallestScreenWidthDp >= 600`) so the viewed widget receives fresh pixels first.
- **Interaction preemption (`WidgetUpdateTracker.cancelOtherJobs`)**: When a user interaction arrives for widget $A$, background render coroutines for any widget $B \neq A$ are immediately cancelled, freeing CPU and thread resources for the user-facing interaction. Logged with `WIDGET_INTERACTION_PREEMPTION active=… cancelled=…`.
- **Debounced preemption settlement (`WidgetPreemptionSettler`)**: Preempted widgets are tracked and repainted from memory cache 3 seconds after interactions settle (`WIDGET_PREEMPTION_SETTLE widgets=…`), guaranteeing secondary widgets do not remain stranded on stale bitmaps.

Android screen-on receivers registered at runtime work only while the process is alive. Manifest
`USER_PRESENT` delivery has measured unreliable, and manifest `ACTION_SCREEN_ON` registration is not
a substitute for the runtime receiver.

## Related code

- Forecast cadence: `ForecastHorizonCadence.kt`
- Background wakes: `ForecastSchedule.kt`; Android `WidgetWorkScheduler.enqueueDueWakeSync`,
  `FullSyncPipeline.kt`, `ScreenOnReceiver.kt`; desktop `DaemonRuntime.kt` forecast loop
- Pre-unplug fetch: `PreUnplugFetchRunner.kt`, `UnplugPredictor.kt`, `ChargeHistory.kt`; Android
  `PreUnplugFetchScheduler.kt`, desktop `DaemonRuntime.kt` / `DesktopChargeHistoryStore.kt`
- Observation cadence: `CurrentTempCadence.kt` and `NonPrimaryObservationPolicy.kt`
- Targeted hourly coverage: `HourlyOnDemand.kt` and `HourlyOnDemandRun.kt`
- User-initiated refresh throttle: `UserRefreshThrottle.kt`
- Snapshot timing: `HourlySnapshotRun.kt`
- Desktop planning: `ForecastSchedule.kt` and `DesktopForecastPlanner.kt`
