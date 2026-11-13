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
| Charging or battery ≥ 70% | On | 10 hours |
| Charging or battery ≥ 70% | Off | 10 hours |
| Battery < 70% | On or off | 24 hours |

- A fetch stores every returned daily row and appends up to eight days of hourly. Daily cloud/precipitation summaries are computed from the whole download before
  hourly trimming.
- Freshness uses one per-source, per-site LONG marker, not a forecast row's `fetchedAt`.
- Manual Refresh, source switches, location changes, and screen-on catch-up also use LONG when their
  own policy requests a forecast.
- On a charger, a forecast also comes due 45 minutes before the device is predicted to leave the
  charger (see [pre-unplug fetch](#pre-unplug-fetch)), and the overnight fetch that would be overwritten
  first is skipped.
- **A source the user reaches for stays within 2 hours** at battery ≥ 70% or on a charger: neither
  platform can tell what is on screen, so a screen coming on never fetches. Tapping a widget (Android),
  opening the popup (desktop), or opening Forecast History or Observations (both) checks that one source,
  and past the limit forces a fetch of it alone (`ViewingForecastRefresh`; log `VIEWING_FORECAST`). "Last
  fetched" is the newest of the fetch marker and the source's last successful check. A refresh repaint or
  resize is not a tap, so it checks nothing. Any refresh a tap or click starts, a source switch or arrow
  included, shows the queued and completed messages; a tap that starts none shows nothing. A request
  blocks another for that source for 15 minutes, so a failing provider is not retried on every tap.
- A screen-on moves the next wake to the screen-on due time (Android `SCREEN_ON_FORECAST`). The desktop
  forecast loop re-plans on every screen wake, which has the same effect. Since 2026-11-05 screen-on and
  screen-off share the 10-hour interval, so this no longer moves the wake earlier; a source the user
  reaches for still refreshes within 2 hours.
- Switching to a source fetches its forecast when it is at least 2 hours old at battery ≥ 70% or on a
  charger, otherwise 4 hours (`SourceToggleRefreshPolicy`; desktop
  `LAUNCH_REFRESH_CHECK reason=source_change`).
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
  one (`trigger=missing`). The periodic worker is a 24-hour backstop (`periodic_1440m`). A screen-on
  with no viewed source past its limit brings the due-time sync forward to the 4-hour screen-on due
  time (`SCREEN_ON_FORECAST outcome=replanned`).
- **Android start:** a widget update that finds the forecast stale queues one delayed sync
  (`WidgetStartupCoordinator`, `reason=on_update_stale`), so an install or emulator start fetches
  without waiting for the planned wake.
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

Nobody sees a screen-off reading: a screen-on fetches when the last one is a full screen-on interval
old (charging ≥ 10 minutes, battery ≥ 20 minutes) and otherwise re-plans the loop so its next run is
that interval after the last fetch (`CurrentTempCadence.screenOnCatchUp` → `SCHEDULE`; a pass chosen
with the screen off would otherwise wait out its 60 minutes with the screen on), and history is refilled (NWS gap fill; every forecast fetch takes
Tomorrow.io's last 23 hours). The armed one-shot notification is the exception. The opportunistic
45-minute band never fetches on a charger (the loop covers it).

An unplug the app did not hear is placed at the last charging sighting if it is within the screen-off
charging interval plus 15 minutes (`ChargeHistory.MAX_ESTIMATE_GAP_MS`); a screen-on is a sighting too.

Android's delayed current-temp loop work keeps its `CONNECTED` constraint: JobScheduler lifts the
background network block (`blockedReasons=0x40`) for a running job only when the job declares one.
While Samsung Freecess has the app frozen (`blockedReasons=0xc0`, OEM deny), the job cannot become
ready until something unfreezes the app; the screen-on `UiTickWorker` does that every ~2 minutes.

Android uses charging WorkManager, battery screen-on alarms, and an opportunistic JobScheduler band;
desktop uses its observation loop. The desktop loop counts each wait from the last observation fetch
(`CurrentTempCadence.remainingMs`) and re-plans on a wake: the ScreenSaver D-Bus unblank, a screen
off → on edge seen by the 60-s presence read (`DesktopPresence`; the only signal when a monitor leaves
DPMS standby without a lock), or a plug/unplug. Each plan logs `OBS_LOOP_PLAN screenOn=… intervalMin=…
waitSec=… [wake=…]` to `app_logs`. A loop restart (every source switch or settings save) keeps that
deadline: the restarted loop waits for its launch refresh (at most 2 min, logged as `OBS_LOOP_START`),
then counts from the last observation fetch, never from the restart. Screen-on catch-up fetches only when the relevant observation age
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

### Interaction cache and the stored-data version

Android's widget interaction path (`DailyInteractionRenderer`) reads its daily rows, actuals,
snapshots and hourly through `WidgetInteractionCache`, kept 60 s per site, day, load window and
hourly source set, so a source switch never reads another source's hours. Its actuals come from the
same `DailyActualsStore.getDailyActualsWithLiveToday` (`:shared` `DailyActualsAssembler`) as worker
paints, so a tap draws the same today — live fog, gated low — as the refresh before it
(`plans/261111-tap-render-today-fog-shared-actuals.md`). An entry also ends at the next stored-data write: the shared write runners (`ForecastPersistRun`,
`CurrentConditionsRun`, `NwsGapFillRun`) bump `StoredDataVersion` after they store rows, and
`VersionedTtlCache` (`:shared`) misses on any bump since the entry's load began. So the UI-only
repaint a screen sends after its fetch (History, Observations) draws the fetched rows, without each
sender having to clear the cache. Worker paints and user-initiated widget refreshes still clear it
outright. Desktop keeps no such cache; its windows reload on `dataUpdateCount`. The trace is
`STORED_DATA_VERSION reason=… version=…` at VERBOSE, off by default on Android
(`adb shell setprop log.tag.STORED_DATA_VERSION VERBOSE`); a cache reload shows as a non-trivial
`DailyInteractionRenderer … dailyDataMs=` on the repaint after a fetch.

### Unforced fetch throttle (Android, 10 minutes per source)

An unforced Android sync drops from its `ForecastFetchPlan` any due source tried at that site in the
last 10 minutes (`Plan.withoutRecentlyTried`; `FetchMetadata` stores each try, successful or not). The
limit is per source: fetching others never holds back one that is due. Held sources log
`NET_FETCH_THROTTLED sources=…`. Forced fetches and a site with no cached forecast skip the limit.

A forced refresh for one source queues behind a pending all-sources sync unless one for that source
is already pending, because an all-sources sync skips gated sources. If it did fetch the source, the
follow-up coalesces under the repository lock (`ForcedRefreshSatisfaction`). Missing-actuals refreshes
force only the displayed source.

### User-initiated refresh throttle (10-minute limit)

User-initiated manual refresh triggers (Android widget tap-to-refresh, Settings "Refresh data",
Forecast History refresh, Observations refresh, and Desktop UI refresh buttons) bypass routine
background cadence checks so that an explicit user tap can fetch immediately. However, manual user
refreshes are limited across both platforms to at most once every 10 minutes per weather source
(and observations scope) via `UserRefreshThrottle`:

- **Throttled (< 10 minutes since last user refresh of that source)**:
  - Network syncs and gap fills for that source are suppressed.
  - A user-visible notice is shown (Android home widget message banner, in-app `Toast.LENGTH_SHORT`
    on Settings/History/Observations, or Compose `RefreshNoticePill` on Desktop):
    `"Not doing because last refresh was X minute(s) ago, and wait another Y minute(s) before asking for a refresh"`.
  - Logged with `USER_REFRESH_THROTTLED source=… elapsedMin=… waitMin=…` and `REFRESH_TOAST … none=RATE_LIMITED source=…`.
- **Allowed (≥ 10 minutes or first user refresh for that source)**:
  - Last user refresh timestamp for that source is recorded (`FetchMetadata` per source on Android, `lastUserRefreshMsBySource` on Desktop).
  - Immediate forced forecast sync and history gap fill are enqueued.
  - A three-second notice names the source and says the refresh is queued; another says how it ended
    ("NWS refreshed"). A failure names the fetched source, why, and when it is retried
    ("Meteo: Timed Out · retrying 9:20 PM"; "Couldn't refresh NWS" only when the reason is unknown).
    Desktop's source-switch notice takes the fetched source from the daemon's `SWITCH_REFRESH_RESULT`
    row, not the source displayed when the fetch ends. The queued notice shows before the cache repaint, which
    can take seconds. Only Settings' Refresh data covers every source, so its messages name none
    ("Refresh complete", "Refresh failed"); every other refresh request forces one source.
- **Passive events are exempt**:
  - Background periodic worker ticks, power connect/disconnect events, screen unlock repaints
    (`EXTRA_PASSIVE = true`), and UI-only ticks do not check, update, or get blocked by this throttle.

### Failed-fetch retry (`SourceRetry`, shared)

A forecast fetch of a displayed source — or of any source in Settings → Refresh data, which the user asked
for (`SourceRetry.onFailure(requested = true)`; the retry chain then runs to its cap even for a source no
screen shows) — that fails with a transient code (`TIMEOUT`, `DNS_ERROR`,
`CONN_REFUSED`, `SOCKET_ERROR`, `SSL_ERROR`, `HTTP_5xx`) arms a retry of that source after 1, 2, 5 and
10 minutes (`SourceRetry.DELAYS_MS`, 4 per failure streak); a success ends the streak, and a failure
more than an hour after the last retry starts a fresh one. Quotas, rate limits, 4xx and `NO_COVERAGE`
are not retried here. On battery with the screen off a retry is neither armed nor run
(`reason=battery_screen_off`, `fire_skip`); a skipped retry ends its chain. Without it, a tap-stale
source that timed out waited for its background cadence (hours).

- Android: prefs `source_retry_at_<id>`/`_delay_`/`_attempt_` (cleared on success) and a delayed
  forced sync targeted at the source (`weather_widget_source_retry_<id>`, reason `source_retry`,
  silent).
- Desktop: a `SOURCE_RETRY_ARMED` contract row (`consumed=true` once it came due); the forecast loop sleeps toward it
  (`FORECAST_LOOP_SLEEP reason=SOURCE_RETRY next=<source>`, the earliest armed retry of any source) and fetches
  that source in full when it is due (a refresh-all's failed source is fetched through its own repository).
  The UI's fetches run in the daemon too (`fetch-request.sock`), so their failures arm the same retry.
- `SOURCE_RETRY scheduled|skip` logs every decision on both platforms. The failure pill's detail reads
  "Timed Out · retrying 9:20 PM" while the retry is ahead, the failure time otherwise.

### Multi-widget render prioritization and interaction preemption

On devices with multiple home-screen widgets (or foldables like Galaxy Z Fold across cover and inner displays), widget updates avoid competing with the user's active view:

- **Priority ordering (`WidgetPaintPrioritizer`)**: Batch repaints (worker sync completion, screen-on catch-up, cache refreshes) render the most-recently-interacted widget at index 0. Remaining widgets are ordered by form factor relevance (e.g. inner vs. cover display on foldables matching `smallestScreenWidthDp >= 600`) so the viewed widget receives fresh pixels first.
- **Interaction preemption (`WidgetUpdateTracker.cancelOtherJobs`)**: When a user interaction arrives for widget $A$, background render coroutines for any widget $B \neq A$ are immediately cancelled, freeing CPU and thread resources for the user-facing interaction. Logged with `WIDGET_INTERACTION_PREEMPTION active=… cancelled=…`.
- **Debounced preemption settlement (`WidgetPreemptionSettler`)**: Preempted widgets are tracked and repainted from memory cache 3 seconds after interactions settle; a later tap restarts the wait (`WIDGET_PREEMPTION_SETTLE widgets=…`), guaranteeing secondary widgets do not remain stranded on stale bitmaps.
- **Refresh broadcasts release immediately (`WidgetActionReceiver`)**: Every `ACTION_REFRESH` finishes its `goAsync()` result right after launching its work. Android delivers this app's manifest broadcasts one at a time, so holding the result through a repaint queues every tap behind it.
- **Timed repaints wait for the user (`TimedRepaintGate`, shared)**: `ui_update_alarm`, `clear_refresh_notice`, the Android startup follow-up, and desktop's fallback reload defer during an interaction or user refresh and for 3 seconds after it (`TIMED_REPAINT_DEFERRED`). A trigger deferred for 30 seconds runs anyway (`TIMED_REPAINT_FORCED`). Android hands deferred widgets to `WidgetPreemptionSettler`; desktop retries on its next tick. Resume reloads, location changes, user refreshes, and fetch results are never gated.
- **UI tick (`UIUpdateScheduler`, `UiTick`)**: the `ui_update_alarm` tick (2 minutes on a charger) is a delayed unique `UiTickWorker` job (`ui_tick`, no constraints) while the screen is on, and an `RTC` `setAndAllowWhileIdle` alarm while it is off (`UI_TICK_SCHEDULED via=job|alarm`, VERBOSE). Samsung Freecess freezes the idle widget process and holds all its alarms, wakeup ones included, until a tap or screen change; a job start unfreezes it. A tick that reschedules itself while running appends a successor rather than replacing itself (`UiTick.tickWorkPolicy`).
- **One repaint per banner (`FetchBanner.show`)**: A UI repaint paints every widget, so a banner schedules one however many widgets show it.

Android screen-on receivers registered at runtime work only while the process is alive. Manifest
`USER_PRESENT` delivery has measured unreliable, and manifest `ACTION_SCREEN_ON` registration is not
a substitute for the runtime receiver.

## Started hours and stale forecasts

Every hour, started or not, shows the newest fetch (`SettledForecastPick`); a fetch during or after
the hour may be a hindcast and still wins. The pre-hour freeze was removed 2026-11-04
([plan](../plans/261104-remove-hourly-pre-hour-freeze.md)). Every fetch also files the elapsed hours
its payload still carries (NWS gridpoint since the issuance, Open-Meteo `past_days`, Silurian
`include_past`, Tomorrow.io 23 h; `ElapsedForecastBackfill`), so a device that slept recovers them on
its next fetch ([plan](../plans/261104-file-every-elapsed-hour.md)). Hours older than a source's
look-back keep the last forecast the device fetched, and devices can still disagree there. The hourly
temperature, rain-chance, and cloud graphs on both platforms draw such an hour's forecast line at
reduced opacity when its newest fetch was more than 3 h before the hour (`:shared`
`StaleForecastSpans`). The cloud graph's day-ago prediction is never dimmed.
See [plans/261104-dim-stale-pre-hour-forecasts.md](../plans/261104-dim-stale-pre-hour-forecasts.md).

## Related code

- Forecast cadence: `ForecastHorizonCadence.kt`
- Background wakes: `ForecastSchedule.kt`; Android `WidgetWorkScheduler.enqueueDueWakeSync`,
  `FullSyncPipeline.kt`, `ScreenOnReceiver.kt`; desktop `DaemonRuntime.kt` forecast loop
- Pre-unplug fetch: `PreUnplugFetchRunner.kt`, `UnplugPredictor.kt`, `ChargeHistory.kt`; Android
  `PreUnplugFetchScheduler.kt`, desktop `DaemonRuntime.kt` / `DesktopChargeHistoryStore.kt`
- Observation cadence: `CurrentTempCadence.kt` and `NonPrimaryObservationPolicy.kt`
- Targeted hourly coverage: `HourlyOnDemand.kt` and `HourlyOnDemandRun.kt`
- User-initiated refresh throttle: `UserRefreshThrottle.kt`
- Hourly writes: `ForecastPersistRun.kt`
- Desktop planning: `ForecastSchedule.kt` and `DesktopForecastPlanner.kt`
