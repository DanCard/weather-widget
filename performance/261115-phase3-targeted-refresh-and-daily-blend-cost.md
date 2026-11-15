# Phase 3: targeted refreshes fetch one source; cut the daily blend's cost

Status: approved 2026-11-15. B1 and B2 implemented and measured; Part A and B3 not started.

Phase 3 of `performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`. It replaces
that file's original phase 3 design (an idle housekeeping job), which the audit recorded there
showed to be partly decided already and partly aimed at the wrong cost. Everything below is a
Claude proposal unless marked as a user decision.

## Goal

Make the app do less work in the background, so there is less to collide with drawing the widget.

Phases 1 and 2 changed only when background work runs and at what priority. They do not make
that work any smaller. Phase 3 does, in two places:

1. **Stop fetching sources nobody asked for (Part A).** When the widget refreshes one source,
   for example NWS after a resize, the app also fetches every other source that happens to be
   due. On the slow boot seven sources were fetched when one was wanted. Phase 3 fetches the one;
   the others wait for the regular scheduled sync.
2. **Stop the daily high/low calculation doing three times the work it needs (Part B).** After
   new observations arrive, the app recalculates each day's actual high and low. To do one day it
   calculates three and throws two away. On the Pixel that takes 12-18 s per day. Calculating
   only the day that is kept should leave about a third of that work; this is not measured yet.

The second matters most, because the same calculation is also:

- the slowest step when the widget first draws after the app starts (about 8 of the 14 s on the
  Pixel), and
- the likely reason Android killed the app for using too much CPU on 2026-11-14.

In one line: phases 1 and 2 keep background work out of the widget's way; phase 3 shrinks the
background work itself.

Two points about Part B:

- The waste exists because the calculation needs a day of context on either side to get a day's
  edges right, and the code blends every date in that window instead of using the neighbours
  only as context.
- Skipping the two discarded days cannot change any number on screen: their results are already
  thrown away.

The original phase 3 idea, pushing heavy work to when the phone is idle, is set aside (Part C):
making the work cheap is better than rescheduling it.

## Why the original design changed

- **User decision 2026-10-10** (`performance/261010-fetch-only-sources-likely-to-be-viewed.md`):
  background fetches are limited to sources displayed or viewed in the last 8 days
  (`:shared` `SourceFetchGate`), and losing accuracy tracking for unviewed sources is accepted.
- **User decision 2026-10-19**
  (`performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md`): desktop fetches
  nothing while nobody is at the machine. "Fetch housekeeping when the device is not in use"
  contradicts it.
- The expensive part of a sync on the Pixel is not the fetching. It is the daily blend
  recompute, and a third or less of its work is kept.

## Evidence

Pixel 7 Pro, debug build, `app_logs` of 2026-11-15.

| Observation | Row |
|---|---|
| A resize refresh targeting NWS fetched all seven sources | `SYNC_START force=true target=NWS reason=stale_on_resize`, then `FETCH_PLAN parts=` seven sources, `NET_FETCH_COMPLETE durationMs=98484` |
| A warm past-day recompute costs 12-18 s | `DAILY_RECOMPUTE_PERF date=2026-11-12 total=19606ms … extremes=17925ms contextObs=6777 dayObs=2213` |
| A sync after a backfill recomputes four days | 06:43-06:44: four rows, 54 s of `extremes` in total |
| The cold startup paint's largest step is the same blend | `loadStartupData … extremesMs=8203` with no sync running (0.4-0.7 s warm) |
| Android killed the app for background CPU | `PROC_EXIT reason=EXCESSIVE_RESOURCE_USAGE … excessive cpu` 2026-11-14 22:01 |
| The emulator does the same recompute in about 0.5 s | `DAILY_RECOMPUTE_PERF … extremes=455ms contextObs=1946 dayObs=760` |

### Where the blend's work goes (read from the code)

`DailyBlendRecomputeRun.day` loads the target day's observations plus 24 h either side
(`DAILY_BLEND_CONTEXT_MS`) and calls `ActualsAggregator.aggregateStored`, then keeps one date:

```kotlin
.aggregateStored(observations = contextObs, …)
.filter { it.date == dateKey }
```

Inside, `ActualsAggregator.aggregate` does this for every source group:

```kotlin
obsList.groupBy { /* local date */ }.mapNotNull { (date, dayObs) ->
    …
    val extremes = blendDailyExtremesViaSeries(contextObs = windowObs, …)
```

A 72 h window holds three local dates, so each source gets three full series blends and two are
discarded. The discarded ones could not be used anyway: their own ±24 h context is cut off by the
window. `DailyBlendRecomputeRun.range` then repeats this per day.

The same function serves the live-today read (`DailyActualsAssembler.assemble`), which passes
`[today − 24 h, tomorrow)` and so also blends yesterday for every source. Whether any of
yesterday's rows are used there has not been checked (`gatedLive`, line 123).

Not yet known: how the remaining cost divides between the series blend itself, the per-source
repetition over shared provider observations, and Pixel-versus-emulator speed. Step B1 measures it.

## Part A: a targeted refresh fetches its target only (Android)

Today `ForecastFetchCoordinator.planSync` forces the target and also takes every gated-in source
whose cadence is due. A refresh the user triggered for one source should not start six more
fetches, their parses and their snapshot writes beside the paint.

1. In `:shared` `ForecastFetchPlan`, add a plan step that holds every non-target source's parts
   when the fetch is a targeted force, keeping each source's `nextDue` so the scheduler still
   knows when it is owed.
2. `planSync` applies it when `forceRefresh && targetSourceId != null`. Untargeted forces
   (Settings → Refresh data, location change) and scheduled syncs are unchanged.
3. Log the held sources on the existing `FETCH_PLAN` line (`held=`), one line on both platforms.
4. Before coding: confirm `DueWakePlanner` and the periodic sync pick up a source that is
   already past due, so a held source is fetched by the next scheduled sync and not stranded. If
   they do not, that is fixed first.
5. Desktop: its targeted refresh already fetches the displayed source only. The shared step is
   applied there too so the rule has one definition; no behavior change is expected, and a test
   pins that.

Tests (must fail on the current code):

- Shared `ForecastFetchPlan` test: targeted force with three due sources fetches one, holds two,
  and keeps their `nextDue`.
- Android `ForecastFetchCoordinator` test: `stale_on_resize`-style targeted force plans the
  target only; an untargeted force and a scheduled sync plan what they do today.
- Desktop planner test: targeted refresh plan is unchanged.

Device check (Pixel): age the data or wait for it to go stale, resize a widget, confirm
`FETCH_PLAN` fetches one source with `held=` listing the rest, and that the held sources are
fetched by the next scheduled sync.

## Part B: cut the daily blend's cost (`:shared`, both platforms)

### B1. Measure first

Add stage timing inside `ActualsAggregator.aggregate` behind the existing
`DAILY_RECOMPUTE_PERF` row: number of source groups, dates blended, dates kept, and time in
`blendObservationSeries`. One forced recompute of a past day on the Pixel gives the split. This
decides how far B3 needs to go.

### B2. Blend only the dates the caller keeps

1. `ActualsAggregator.aggregate` and `aggregateStored` take the set of dates wanted (default:
   all, so other callers are unchanged). Dates outside it are skipped before
   `blendDailyExtremesViaSeries`.
2. `DailyBlendRecomputeRun.day` passes its one date.
3. `DailyActualsAssembler.assemble` passes the dates it reads, once the check above settles
   whether that is today alone.
4. The output for a kept date must be identical: same observations, same window, same blend.

Expected effect: about a third of today's blend work per recomputed day, from the code. Not
measured.

Tests:

- Equality test on a multi-day, multi-source fixture: rows for the wanted date from the filtered
  call equal the same date's rows from the unfiltered call, field for field.
- A test that fails on the current code: the series blend is invoked once per source for a
  one-date recompute, not once per date in the window.
- Existing `DailyBlendRecomputeRun`, `ActualsAggregator` and `DailyActualsAssembler` tests stay
  green on both platforms.

### B3. Only if B1 shows it is needed

Candidates, each needing its own equality proof before it is taken:

- Sources that share a provider's observations repeat the station blend; share the part that does
  not depend on the source's own forecast.
- `range` re-reads and re-groups overlapping windows per day; read once for the range.

### Measurement

Same Pixel, same day's data, before and after: `DAILY_RECOMPUTE_PERF extremes=` for a forced
past-day recompute, and `extremesMs` in `WIDGET_STARTUP_PERF` on a cold start. About three runs
in total. The JVM suites run in full, since `:shared` changes reach desktop.

### Results so far (2026-11-15)

Implemented: stage timing in `ActualsAggregator.aggregate` (`AggregateStats`, on
`DAILY_RECOMPUTE_PERF`, the new VERBOSE `DAILY_RECOMPUTE_BLEND`, and Android's `DAILY_ACTUALS_PERF`
for the live read), and a `wanted(source, date)` predicate that skips rows the caller drops. The
recompute wants its one date. The live read wants today, plus a past date only where no stored row
exists for that source, because a stored row wins there anyway. JVM suite: 7,063 passed.

Pixel 7 Pro, same day's data, sync on the phase 2 pool:

| Run | Blends | `extremes` | Series blend | Rain totals |
|---|---|---|---|---|
| Today, before B2 | 14 (7 sources x 2 dates) | 10.1 s | 4.3 s | 5.0 s |
| Today, after B2 | 7 | 5.5 s, 4.8 s | 2.4 s, 1.9 s | 2.4 s, 2.4 s |
| Past days, after B2 | 7 (14 skipped) | 11.3 s, 9.8 s, 8.1 s | 5.1 s, 4.4 s, 3.3 s | 5.0 s, 4.3 s, 3.8 s |

- **B1's surprise: the rain-total step costs as much as the series blend**, on a dry day.
  `ObservedPrecip.daily` runs once per source per date over largely the same provider rows.
- **B2 halves today's recompute**, as the blend count predicts.
- **Past days have no like-for-like "before".** Earlier the same day, off the pool, they took
  12-18 s; on the pool after B2 they take 8-11 s. The pool itself appears to slow this step
  (today's took 2.4-5.3 s off the pool before B2 and 10.1 s on it), so the two effects are mixed.
- **The cold startup paint did not improve:** `extremesMs` 8.3 s (was 8.2 s). Its split:
  `readMs=1925 assembleMs=6390 groups=3 blended=5 skipped=1 blendMs=3152 precipMs=2517`. Only one
  blend was skippable: the paint displayed NWS alone, but two more source groups that borrow the
  same observations were blended for both dates, and they have no stored rows to defer to.

What this means for B3 (needs approval; each changes more than B2 did):

- **Blend only the sources the caller displays** in the live read. On this paint that is one
  group of three. It changes what the read returns for sources nobody asked for, so the callers
  that reuse a loaded bundle after a source switch must be checked first.
- **Compute the rain totals once per provider's rows**, not once per source.
- **Decide whether the recompute belongs on the low-priority pool**, now that it measures slower
  there.

## Unrelated finding: Fold 4 blank hourly graph (2026-11-15 14:43)

The user reported the Fold "missing displaying something". The inner-screen hourly widget (392)
was blank after a day tap on Sun Nov 22 (`CLICK_DAILY date=2026-11-22 … col=9`). NWS hourly data
on the Fold ends at Nov 22 00:00, so the view's 03:00-21:00 window has no rows
(`TEMP_GAPS … missing=19`). No "No hourly forecast" message appeared because the rule returned
`action=Nothing`: `NoHourlyChecker.hasHourlyForDay` counts a day as covered from two points, and
the midnight row plus a gap-fill row are enough. Not caused by this plan's changes: none of them
touch the day-tap or gap rules. Not fixed; proposal pending.

## Part C: idle housekeeping job (not planned)

Deferring past-day recomputes and deep backfills to a charging and idle job, as first proposed.
Built only if Part B leaves a past-day recompute expensive enough to matter beside a paint.

## Order

A and B are independent. Proposed order: B1, B2, measure, then A, then B3 if warranted. B comes
first because it also shortens the cold startup paint, which phases 1 and 2 do not touch.

## What this will and will not fix

- B2 should shorten every recompute and probably the cold paint's largest step. The size of the
  gain is an estimate from the code until B1 and the measurement are done.
- A removes ride-along fetches from user-triggered refreshes. It does nothing when all sources
  are due on a scheduled sync.
- Neither makes a debuggable build AOT-compiled; cold starts stay slower than a release build.
- The excessive-CPU kill is probably driven by the recompute, but that link is inferred from
  timing, not shown.

## Still open from phases 1 and 2

The Pixel reboot with stale data, which is the real test of the startup hold and the first look
at the sync pool on a cold process.
