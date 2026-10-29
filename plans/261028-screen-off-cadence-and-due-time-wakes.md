# Screen-off current-temp cadence and due-time background wakes (Android + desktop)

## Problem

Fold-4, 24 h ending 2026-10-28 22:48 (app_logs):

- **Screen-off current temp:** 47 runs (23 `charging_loop` at 30 min + 24 `opportunistic_job` at 45 min
  overlapping it; 44 `CURR_FETCH_FRESH_SKIP`). Station readings at the 20:48 fetch were 13–61 min old —
  most NWS stations report hourly, so a 30-min poll mostly re-reads the same METAR. Nobody sees a
  screen-off result: a charging screen-on already fetches when the last fetch is ≥ 15 min old
  (`CurrentTempCadence.screenOnCatchUp`), and the widget estimates between fetches.
- **Android hourly worker:** 20 `periodic_60m` syncs, 1 led to a forecast fetch (screen on); the 12
  screen-off ticks fetched nothing yet each did 4–14 s of work (`SYNC_PERF`, mostly actuals recompute).
  The 60-min tick also applies on battery ≥ 80 % (`BatteryTier.treatAsCharging`), screen on or off.
- Android's tick is its forecast scheduler, so lengthening it alone makes an 8 h fetch up to a tick
  late. Desktop already sleeps until the next due fetch (shared `ForecastSchedule`, 2 h cap).

What a slower screen-off cadence must not lose:

- **Tomorrow.io 5-min actuals:** safe. Current-temp fetches take a 1 h window
  (`INCREMENTAL_ACTUALS_LOOKBACK_HOURS`), but every forecast fetch (≤ 8 h on a charger) takes 23 h
  (`FULL_ACTUALS_LOOKBACK_HOURS`), refilling any gap.
- **NWS station history:** refilled by `NwsGapFillRun` / `OBS_HOURLY_BACKFILL`.
- **Dominant-station notification** (`DominantTempWatch`, one-shot, armed from Settings): the user waits
  with the screen off, so it keeps today's cadence while armed.

## Design — everything decided in `:shared`

### 1. Screen-off current-temp cadence (`CurrentTempCadence`)

| Power | Screen on | Screen off (watch disarmed) | Screen off (watch armed) |
|---|---|---|---|
| Charging | 10 min | **60 min** (was 30) | 30 min |
| Battery ≥ 70 % | 20 min | **none** (was 45) | 45 min |
| Battery 66–69 % | 45 min | none | none |
| Battery ≤ 65 % | none | none | none |

- New parameter `watchArmed: Boolean` on `intervalMinutes` / `loopIntervalMinutes` /
  `opportunisticAllowed`; both platforms pass `DominantTempWatchState.armed` (Android
  `DominantTempWatchPreferences`, desktop `DominantTempWatchStore`).
- 60 min matches the hourly METAR. Battery screen-off polls nothing unless the watch is armed (the
  66–69 % band already did): a battery screen-on fetches when the last fetch is ≥ 20 min old, and
  history is refilled (NWS gap fill; Tomorrow.io's 23 h window on each forecast fetch).
- `opportunisticAllowed` returns false on a charger: the charging loop already covers it (Android-only
  job; the rule lives in the shared function).
- Arming the watch wakes the loop so the 30-min band starts now (Android: re-schedule the charging loop;
  desktop: wake the observation loop).

### 2. One background-wake planner for both platforms (`:shared` `BackgroundWakePlan`)

Generalises desktop's `ForecastSchedule.sleep` from "next forecast" to "next due background duty":

- Inputs (each a due instant or null): every source's `ForecastHorizonCadence.Decision.dueAtMs` (already
  includes the pre-unplug lead), the armed pre-unplug check, and any time-bound duty the audit (step 4)
  finds. Output: `Wake(atMs, reason)`, capped at `MAX_SLEEP_MS`, floored at `MIN_SLEEP_MS`, with the
  existing failure retry.
- `MAX_SLEEP_MS` 2 h → **24 h** (the longest forecast interval), one value for both: both platforms now
  hear what changes due times (power: Android plug-in job + `ChargeStateReceiver`, desktop UPower;
  screen: `ScreenOnReceiver`, desktop ScreenSaver; resume/network on desktop; location moves trigger
  their own fetch), and a screen-on heals a lost wake (charging: fetch when due; battery: re-plan). In
  normal operation the cap never binds; it backstops a planner bug.
- Logged as today's `FORECAST_LOOP_SLEEP` line on both (`sleepMin=… reason=DUE|CAP|RETRY next=…`).

### 3. Hosts

- **Desktop:** forecast loop calls `BackgroundWakePlan` instead of `ForecastSchedule.sleep` directly
  (same inputs + the cap change).
- **Android:** at the end of every non-UI full sync, enqueue one delayed `WeatherWidgetWorker` at the
  plan's wake (`WORK_NAME_DUE_WAKE`, delayed work → `REPLACE` allowed; network constraint). The periodic
  work becomes a **24 h backstop** (`ExistingPeriodicWorkPolicy.UPDATE`), not the scheduler.
  `ForecastFetchPolicy.periodicTickMinutes` / its battery tiers go away.
- `ScreenOnReceiver` (battery, screen on) re-plans — no fetch — since screen-on shortens the forecast
  interval 8 h → 4 h; on a charger it already fetches when due (`screenOnCatchUp`).

### 4. Duty audit before switching Android (gate)

The hourly full sync also carries non-fetch duties. Before removing the hourly tick, list each with its
desktop trigger and give anything time-bound a due instant in step 2:

| Duty (Android full sync) | Desktop trigger today | Time-bound? |
|---|---|---|
| GPS resample (passive) | n/a (config location) | no — screen-on resamples |
| NWS gap fill (`NwsGapFillRun.dailyForSources`) | launch/wake catch-up, Refresh | daily; idempotent |
| METAR / Synoptic backfill | observation loop | no |
| Actuals recompute / `DailyHistoryRun.upkeepIfDue` | after each fetch | idempotent; a past day shows its frozen row until it runs |
| History snapshot prune | `pruneHistorySnapshotsIfDue` | daily |
| Pre-unplug arm, daily log lines | daemon 5-min tick | arm has its own triggers |

Any duty found to need a specific time (e.g. a pre-midnight freeze) becomes a planner input on both
platforms, not an Android tick.

### 5. Keep unplug learning bounded (`ChargeHistory`)

Fewer screen-off sightings would widen the gap between the last charging sighting and an unplug the
app did not hear, and `ChargeHistory.MAX_ESTIMATE_GAP_MS` (45 min) drops such an unplug as unbounded.
Today every recorded unplug is exact (Fold 7/7, Pixel 4/4: `ChargeStateReceiver` heard it), so this is
a fallback, but the two values must not drift apart again.

1. **Sight on screen-on:** `ScreenOnReceiver` calls `PreUnplugFetchRunner.observePower` (no fetch, no
   extra wake). Covers "wake the phone, then unplug" to within minutes. It cannot cover a dead process:
   `ScreenOnReceiver` is runtime-registered like `ChargeStateReceiver`, so when one is alive both are.
2. **Derive the bound from the cadence:** `MAX_ESTIMATE_GAP_MS` = shared screen-off charging interval +
   15 min (60 + 15 = 75 min) instead of a literal, so changing one changes the other. An estimated
   unplug can then be up to an hour early — the safe direction, since a prediction is the cluster's
   earliest unplug and an early fetch is still fresh.
3. **Measure it:** `UNPLUG_RECORDED` gains `gapMin` on every estimate (recorded or skipped), and the
   daily `UNPLUG_PATTERNS` line counts `estimated=n/total`, so a rise in estimates is visible.

## Tests

- `:shared` JVM: cadence table incl. armed column and charging opportunistic = none; `BackgroundWakePlan`
  picks the earliest due, caps at 24 h, retries after failure, includes the pre-unplug check; an
  unplug 70 min after the last sighting is recorded (dropped today) and one 80 min after is not;
  the estimate bound follows the cadence constant. Each must fail on today's code.
- Android Robolectric: a screen-on while charging records a sighting (`lastSeenChargingMs` moves).
- Android Robolectric: a full sync enqueues `WORK_NAME_DUE_WAKE` at the plan's delay; periodic is 24 h;
  arming the watch re-schedules the 30-min loop; battery screen-on re-plans without a fetch.
- Desktop: forecast loop sleep uses the plan (existing `DesktopForecastPlannerTest` extended); armed
  watch shortens the observation delay.

## Verification (24 h each, Fold + Pixel + desktop)

- Screen-off charging: ~24 current-temp fetches/day (was 47 runs), none from `opportunistic_job`.
- Screen-off: no `periodic_*` syncs except the 24 h backstop; a `due_wake` sync at each forecast due time
  and the pre-unplug check.
- Forecast fetches no later than before (`FORECAST_HORIZON` timestamps vs. due times).
- Armed watch: 30-min screen-off fetches until it fires.
- Tomorrow.io 5-min series has no gaps after the next forecast fetch.

## Docs

`arch/hourly-forecast-update-cadence.md` (current-temp table, worker tick → due-time wakes);
KDoc on `CurrentTempCadence`, `BackgroundWakePlan`, `ForecastSchedule`.

## Implementation notes (deviations, 2026-10-28)

- **No `BackgroundWakePlan` class.** The duty audit found nothing that needs a specific clock time:
  snapshot prune is once a day on any fetch, daily-history upkeep once a day on a charger, NWS gap fill
  is need-gated, the midnight repaint has its own alarm, and the battery location floor stays in the
  45-minute opportunistic job (which still runs; only its fetch is gated). Forecast due times already
  include the pre-unplug lead, so the shared `ForecastSchedule` is the planner on both platforms with
  `MAX_SLEEP_MS` = 24 h.
- **Arming the watch needs no explicit wake.** It is armed from Settings with the screen on, so both
  loops are on their screen-on pace and pick up the armed band when the screen goes off.
- **Desktop dead-monitor fallback:** the old 2 h cap also covered a dead UPower monitor; the daemon's
  5-minute charger sighting now wakes the forecast loop on a cadence-tier change instead.
- **Android due-wake safety:** the sync that re-plans is often the due-wake work itself, so the policy is
  APPEND_OR_REPLACE while it is running (never cancelling a running worker, AGENTS.md rule 6) and
  REPLACE otherwise; the pre-unplug check uses the same helper. A battery screen-on may only bring the
  planned wake forward (the planned one can be another source's).
- Screen-on WorkManager reads run in the receiver's goAsync coroutine, not on the main thread.
- **Bootstrap:** only a full sync planned the due-time sync, so after an install the Fold (charging,
  screen off) had none until a screen-on or the 24 h backstop — found on-device when a forced run of the
  periodic job was skipped by WorkManager as early. `DueWakePlanner` now also plans from each
  current-temp run when nothing is pending (`FORECAST_DUE_WAKE … trigger=missing`); battery is covered by
  the screen-on re-plan.
