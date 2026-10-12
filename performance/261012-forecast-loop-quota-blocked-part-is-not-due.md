# Forecast loop: a quota-blocked part is not due

## Problem

On 2026-10-11, from 21:35 to 22:59, the desktop forecast loop woke **once a minute**. Each wake made two
billed Google requests (`currentConditions`, `forecast/days`) and stored no hours:

```
21:36:20 FETCH_PLAN parts=GOOGLE_WEATHER:BOTH hourly=GOOGLE_WEATHER:4h(view=0h,lastHourly=4h,due=true)
21:36:20 GOOGLE_REQUEST endpoint=currentConditions status=200
21:36:21 GOOGLE_REQUEST endpoint=forecast/days status=200
21:36:21 GOOGLE_HOURS_PAGES pages=0 reason=quota_blocked
21:36:26 FORECAST_LOOP_SLEEP sleepMin=1 reason=DUE next=GOOGLE_WEATHER:HOURLY
```

From 12:00 to midnight the desktop made 41 `currentConditions` and 41 `forecast/days` requests,
and `forecast/days` itself hit 429 three times. The loop used up the daily quota too. Earlier that
day, from 12:20 to 14:51, the same state cycled every 15 min (`reason=RETRY`): an hourly-only plan
threw `GoogleDailyQuotaException`, so `fetchFailed` was set.

## Root cause

The planners know nothing about quota blocks.

- `forecast/hours` was refused until midnight Pacific time (`SourceQuotaBlocks`,
  `ForecastProduct.HOURLY`). `GoogleWeatherApi` correctly skips that request.
- The hourly marker (`HOURLY_FETCH`) advances only when hours come home, so `HourlyCadence` keeps
  reporting hourly as **due**.
- In a FULL window, `ForecastParts.withFullWindow` turns HOURLY into **BOTH**. Each wake therefore
  pays for daily and current conditions.
- `ForecastSchedule.dueAtMs` returns an instant in the past, so `sleep` clamps to `MIN_SLEEP_MS` (1 min).

Android's routine plan (`ForecastFetchCoordinator.routineParts`) has the same gap. Its cost is
smaller: one wasted BOTH per periodic tick rather than per minute.

## Fix

A part whose product is refused is **not due until the refusal ends**. The rule lives in `:shared`
and **every routine plan goes through it by construction** (user, 2026-10-12: "should any of this
code be shared?"). The block is a required argument of the one shared composer, so a platform cannot
plan without it.

1. **`:shared` `SourceQuotaBlocks.blockedProducts(sourceId, nowMs)`**: the products refused now.
2. **`:shared` `ForecastParts.forRoutine(dailyDue, hourlyDue, isFull, blocked)`**: `blocked` is
   required, with no default.
   - A blocked product's part is not due.
   - The FULL-window upgrade (HOURLY→BOTH) never happens while DAILY is blocked.
   - An unjudged hourly (`hourlyDue == null`) riding on daily is dropped while HOURLY is blocked.
3. **`:shared` `ForecastSchedule.heldPast(dueAtMs, blockedUntilMs)`**: a part's next due instant is
   no earlier than its block's end. Only desktop sleeps on due instants. Android's WorkManager tick
   just re-plans.
4. **Callers**:
   - Desktop `DesktopForecastPlanner.plan` passes `blockedProducts`, and moves each `Due` to the
     block's end. The loop then sleeps until midnight PT (capped at 2 h) or until daily is due.
   - Android `ForecastFetchCoordinator.routineParts` passes `blockedProducts`.
5. **Logs.** `HourlyCadence.logFragment` adds `,quotaHeldMin=N` when the hourly part is held. Both
   platforms' `FETCH_PLAN` lines then show why an overdue part was not fetched.

Out of scope:
- Forced paths (Refresh, source switch, screen-on), which already have their own quota handling
  (`STALE_REFRESH_SKIP skip=quota_blocked`).
- `SourceQuotaBlocks` persistence. It stays in-process: after a restart, one request meets the
  refusal again and re-records it.

## Tests

| # | Kind | Class | Asserts |
|---|---|---|---|
| 1 | Unit | `ForecastScheduleTest` | `heldPast` returns the block end when it is later than the cadence due; an earlier block and `null` leave it unchanged |
| 2 | Unit | `ForecastPartsTest` | `forRoutine` with HOURLY blocked: FULL + hourly due → null, not BOTH; daily due → DAILY; unjudged hourly + daily due → DAILY. With DAILY blocked: hourly due in FULL → HOURLY (no upgrade); empty `blocked` → unchanged table |
| 2b | Unit | `SourceQuotaBlocksTest` | `blockedProducts` lists only products blocked past now |
| 3 | Unit | `DesktopForecastPlannerTest` | Google, FULL window, hourly overdue, HOURLY blocked → `parts == null` (not BOTH) and the HOURLY `Due.atMs` == block end |
| 4 | Unit | `DesktopForecastPlannerTest` | HOURLY blocked, daily due → `parts == DAILY` (daily keeps its own quota) |
| 5 | Unit | `DesktopForecastPlannerTest` | DAILY blocked, hourly due, NEAR window → `parts == HOURLY` |
| 6 | Integration | `DesktopForecastPlanner` + `ForecastSchedule.sleep` | Replays 2026-10-11 21:36 (charging, lastHourly 4 h, FULL, hours blocked to midnight PT, daily fresh): sleep reason is not RETRY and `ms` ≥ 60 min. The 1-min regression fails this |
| 7 | Integration | `DesktopForecastPlanner` + `SourceQuotaBlocks` + `ForecastSchedule` | Same state with the block expired → hourly due again, plan BOTH (FULL) |
| 8 | Unit (Robolectric) | `ForecastFetchCoordinatorTest` | Routine plan for Google with HOURLY blocked in FULL → no BOTH; with NWS unblocked alongside → NWS still planned |

## Verification

2026-10-12.

- `:shared` `ForecastPartsTest` (8), `ForecastScheduleTest` (8): the blocked-part table, `heldPast`,
  `blockedProducts`. All pass.
- `DesktopForecastPlannerTest` (11): tests 3–7, including the 21:36 replay. It sleeps ≥ 60 min
  (CAP) instead of 1 min.
- `HourlyCadencePlanRoboTest` (12): test 8. NWS hourly blocked in FULL is not planned, an unblocked
  Open-Meteo is, and `requiresNetworkFetch` does not wake for a blocked part.
- Full `:shared:test` (2131) and `:desktop:test` (575) green; `:app:compileDebugUnitTestKotlin` green.
- Desktop rebuilt and restarted (`scripts/buildStart-desktop.sh`). Live confirmation is pending the
  next Google `forecast/hours` 429: expect `FETCH_PLAN … GOOGLE_WEATHER:…quotaHeldMin=N` and no
  1-min `FORECAST_LOOP_SLEEP`.
