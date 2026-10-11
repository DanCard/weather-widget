# Desktop forecast loop: sleep until the next due time, not a 15-minute check

User, 2026-10-11 ("How about having two different timer interrupts instead of looping?"). Follows
`performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md`, which made the desktop
forecast loop (`DaemonRuntime` 3c) wake every 15 min (`FORECAST_LOOP_TICK_MS`) to ask each source
"daily due? hourly due?". That is 96 wakeups a day for roughly 6–20 fetches.

## Goal

Wake when something is due, or when something changed that could make it due sooner — the user's
standing preference for interrupt-driven over polling (memory: best-effort, interrupt-driven).
Behaviour (what is fetched, and when it is due) is unchanged; only when the loop wakes changes.

## Why one scheduler, not two timers

Two independent timers (daily, hourly) would split a fetch that should be one: Open-Meteo, WeatherAPI
and OWM return daily and hourly in a single request, and a source due for both within minutes would be
fetched twice. It is also not two timers but ~7 sources × 2 parts. One loop that sleeps until the
**earliest** due time across all of them keeps the existing per-source combining
(`ForecastParts.forRoutine`) intact.

## Design

```
loop:
  plans  = today's per-source plan (unchanged code)
  fetch whatever is due now (unchanged code)
  next   = ForecastSchedule.nextDueMs(…)            // :shared, pure
  sleep  = min(next − now, MAX_SLEEP_MS)            // cap: see "Events we can't hear"
  wait for `sleep`, or until a wake signal arrives  // whichever first
```

- **`:shared` `ForecastSchedule.nextDueMs`** (pure, unit-tested): for each source, daily due at
  `lastDaily + dailyInterval` (null interval = never) and hourly due at `lastHourly + hourlyInterval`
  (`HourlyCadence`); returns the earliest, or null when nothing will ever be due under the current
  state (e.g. ≤ 50 % battery, no displayed source). Same inputs the plan already reads, so the plan and
  the sleep cannot disagree. Shared rather than desktop-only per the "shared code is the norm" rule,
  and so Android could use it later (today it is a WorkManager periodic tick).
- **Always re-plan on wake.** The sleep length is a forecast; the decision on waking is the existing
  "is it due now?" check with the power/screen/view state at that moment. Waking early costs one plan;
  it never fetches something not due.
- **Wake signal**: a conflated `Channel<Unit>` (`forecastLoopWake`) on `DaemonRuntime`. `wait` =
  `withTimeoutOrNull(sleep) { forecastLoopWake.receive() }`. Signalled (best-effort, fire-and-forget)
  by every in-process event that moves a due time:

| Event | Why it moves a due time | Already a hook? |
|---|---|---|
| Any refresh of a source completes (launch, wake catch-up, viewing refresh, manual, on-demand day) | last daily / last hourly moved | `DesktopWeatherRepository.refreshWithOutcome` / `refreshHourlyOnly` return → signal |
| Resume from suspend | `delay()` runs on CLOCK_MONOTONIC, frozen during suspend: the remaining sleep would be served *after* waking | `kickResumeRefresh` → signal |
| Network restored | a due fetch may have failed while offline | `kickNetworkRestoredRefresh` → signal |
| Config change (display source, location, sources enabled) | everything re-derives | already restarts the fetch loops (`.config-changed`) — nothing new |
| A source switch in the popup / Observations window | view age changes the hourly interval | the UI process writes `source_last_view`; display-source changes already restart loops. A view-only change (Observations cycle) is not signalled — the cap bounds it, and the switch already fetched that source on demand |

### Events we can't hear yet: power and screen

`PowerDetector` reads `/sys/class/power_supply` and `ScreenStateDetector` shells out to `xset` /
`loginctl`, on demand — no events. Plugging the charger shortens cadences (hourly 8 h → 4 h, others
gain 4–12 h tiers); screen on shortens the displayed daily (6 h → 4 h). A sleep computed before the
change is too long.

- **This plan: `MAX_SLEEP_MS = 1 h`.** A missed charger/screen change costs at most an hour. Worst case
  ≈ 24 wakeups/day when nothing is due (vs 96 now); typically far fewer sleeps hit the cap because a
  due time comes first.
- **Not in this plan (follow-up if the hour matters):** UPower `PropertiesChanged` over the system bus
  (`gdbus monitor --system --dest org.freedesktop.UPower`) for the charger, in the same fire-and-forget
  style as the existing logind resume monitor; screen from logind `IdleHint`/X DPMS has no clean
  event. With those, the cap could rise to several hours.

### Daily due grace

Desktop's daily due is `now − lastREFRESH ≥ interval` with no slack; Android uses 2 min
(`ForecastFetchPolicy.isDue`). A sleep computed to land exactly at the due instant can wake a few ms
early and re-sleep for ~0 ms in a spin. Use `HourlyCadence.DUE_GRACE_MS` (2 min) for daily too, and
floor any sleep at 1 min.

## Logging

One line per sleep, so the schedule is auditable from `app_logs`:
`FORECAST_LOOP_SLEEP sleepMin=37 next=NWS:hourly@14:20 capped=false` (VERBOSE when the cap is the
reason, INFO otherwise), and `FORECAST_LOOP_WAKE reason=signal|timeout`. `FETCH_PLAN` unchanged.

## Code

- `:shared` `ForecastSchedule` (new): `nextDueMs(sources: List<SourceDue>, nowMs)`;
  `SourceDue(sourceId, lastDailyMs, dailyIntervalMs, lastHourlyMs, hourlyIntervalHours)`; returns
  `(atMs, sourceId, part)` for the log line.
- Desktop `DaemonRuntime` 3c: replace `delay(FORECAST_LOOP_TICK_MS)` with the wait above; build
  `SourceDue` from the same reads `planFor` already makes (one pass, both uses).
  `FORECAST_LOOP_TICK_MS` removed; `FORECAST_LOOP_MAX_SLEEP_MS = 1 h`, `FORECAST_LOOP_MIN_SLEEP_MS = 1 min`.
- Signal calls: end of `refreshWithOutcome` / `refreshHourlyOnly` (via a callback the runtime passes
  in, so the repository stays unaware of the loop), `kickResumeRefresh`, `kickNetworkRestoredRefresh`.
- CLAUDE.md: the desktop forecast loop sentence in the hourly-cadence paragraph.

## Tests

- `ForecastScheduleTest` (shared, pure): earliest across sources and parts; null interval ignored;
  never-fetched = due now; nothing schedulable = null; grace applied.
- `DesktopForecastLoopTest` (kotlinx-coroutines-test virtual time, fake DAO/clock): sleeps until the
  computed due time, not 15 min; a wake signal mid-sleep re-plans at once; with nothing due the sleep
  is capped at 1 h; the 1-min floor prevents a spin when a due time is already past.
- Integration (2+ classes): a completed `DesktopWeatherRepository` refresh signals the loop and the
  next sleep is recomputed from the new `REFRESH` / `HOURLY_FETCH` markers.
- Manual: on the laptop, `app_logs` over a day shows `FORECAST_LOOP_SLEEP` lines ending at the
  `FETCH_PLAN`/`REFRESH*` times, and a resume produces a `FORECAST_LOOP_WAKE reason=signal`.

## Risks

- **A missed signal** delays a due fetch by at most the cap (1 h). Best-effort by design.
- **Clock jumps** (NTP, manual change, timezone): the wait uses monotonic time; on wake the plan reads
  wall-clock timestamps from app_logs, so a jump is corrected at the next wake (≤ 1 h).
- **Suspend**: covered by the resume signal; without it the heartbeat fallback already kicks.

## Open questions for the user

1. Cap at 1 h, or longer (2–4 h) accepting a later fetch after plugging in the charger?
2. Add the UPower charger event now (lets the cap rise), or only if the hour turns out to matter?

## Decisions (user, 2026-10-11) and changes found before implementing

- **Listen for charger events: yes.** `gdbus monitor --system --dest org.freedesktop.UPower`;
  `PropertiesChanged` lines carrying `OnBattery`, `Online`, `Percentage` or `State`. UPower sends a
  `Percentage` change every percent, so the handler wakes the loop only when the **cadence tier**
  changes (`:shared` `BatteryTier.cadenceTier`: treat-as-charging, > 70 %, > 50 % — every threshold
  `ForecastCadence` / `HourlyCadence` read). A machine with no battery (this workstation: UPower lists
  only `DisplayDevice`) never sends one; the cap covers it.
- **Cap: 2 h** (Claude's call, as asked). With the charger heard, the one change still unheard is
  the screen coming on (displayed daily 6 h → 4 h), and…
- **…screen-on is heard after all**: a `gdbus` ScreenSaver `ActiveChanged(false)` monitor already
  runs (`SCREEN_DETECT`, observation catch-up); it now also wakes the forecast loop.
- **Refresh completion does not signal.** A finished refresh only pushes due times later; the loop
  re-plans on its next wake anyway. (The UI process's refreshes could not signal the daemon in-process
  regardless.)
- **Retry floor after a failed fetch: 15 min** (`ForecastSchedule.FAILURE_RETRY_MS`), the old tick.
  A failed due fetch stays due; without a floor the 1-min minimum would retry every minute offline.
  Network restored wakes the loop sooner.
- **One due test for both parts**: `ForecastSchedule.dueAtMs(last, interval)` = `last + interval −
  2 min` (never fetched = now); the loop's daily check and the sleep use it, `HourlyCadence.isDue` is
  the same formula.

## As built (2026-10-11)

- `:shared` `ForecastSchedule` (`dueAtMs`, `isDue`, `next`, `sleep`) and `BatteryTier.cadenceTier`;
  `HourlyCadence.isDue` delegates to `ForecastSchedule.isDue` (one formula, 2-min grace).
- Desktop `DesktopForecastPlanner`: the loop's per-source plan and due times, extracted from
  `DaemonRuntime` so it is tested against a real DB (one power/screen reading per pass).
- `DaemonRuntime` 3c: settle `FORECAST_LOOP_STARTUP_SETTLE_MS` (5 min) — the launch / catch-up refresh
  runs beside the loop at start; then plan → fetch → re-plan → `withTimeoutOrNull(sleep) { wake }`.
  `FORECAST_LOOP_TICK_MS` removed.
- Wake sources: UPower monitor (`POWER_DETECT`, tier changes only) and the existing ScreenSaver
  monitor. Resume and network restore already restart the loops (`restartWithFreshClients`), so they
  need no signal.
- Tests: `ForecastScheduleTest` (shared), `DesktopForecastPlannerTest` (repository + planner + schedule,
  real SQLite; UPower line parser).
