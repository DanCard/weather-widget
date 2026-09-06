# Thin personal stations, skip settled-day recomputes, defer the rest to screen-off

**Date:** 2026-09-06
**Status:** Parts 1 and 2 done and measured on device; Part 3 deliberately not built — see Outcome

Third in the Samsung tap-latency thread, after
[the read-cost investigation](260906-samsung-tap-latency-observation-read-cost.md) and
[the api-scoped read](260906-scope-observation-read-by-api-and-bound-paint-concurrency.md), which
together took the tap path from 7,975 ms to 342–502 ms and the full sync from 52,162 ms to 9,017 ms.

Those attacked *what a paint reads*. This one attacks *what the app stores* and *what the background
sync recomputes* — now the two largest remaining costs. Every number below is measured on the
reporting device (SM-F936U1, 3 widgets, ~80k `observations` across 71 device locations).

---

## Part 1 — floor personal-station sampling at 10 minutes

### Why

`observations` is 80k rows and the remaining bottleneck is **disk, not CPU**: at identical row counts
a cold read costs `sql=2438 ms` against a warm `sql=353 ms`. Shrinking the table is therefore a
direct win on the first tap after the process is evicted.

Synoptic sampling intervals, measured per station:

| avg interval | stations | type | effect of a 10-min floor |
|---|---|---|---|
| 4.6 min | **KSJC** | **OFFICIAL** | **must be excluded** |
| 4.7–5.8 min | F9422, F4751, G6550, G4110, E8945, E7138 | PERSONAL | halved |
| 10.0 min | 463PG, 496PG, AW020, E3923 | PERSONAL | unchanged |
| 15.0 min | E0597, F3725 | PERSONAL | unchanged |
| 20–105 min | KNUQ, KSQL, KPAO, LOAC1 | OFFICIAL | unchanged |

Only **six** stations are actually affected. They hold ~30,600 of the 43,764 personal rows, so
halving them removes **~15,300 rows ≈ 19% of the whole table**.

What is given up: temperature resolution on stations that are **weighted 0.05**
(`personal_station_discount = 95` → `getPersonalStationWeight() = 1.0 - 95/100`) and that report
**zero sky** — every sky-bearing Synoptic row belongs to KNUQ, KPAO or KSJC, all OFFICIAL.

**The filter must key on `stationType == "PERSONAL"`, never on observed interval.** KSJC reports at
4.6 min and is full-weight, sky-reporting, and the anchor of the NWS blend. An interval-based rule
would catch it. Precedent for the field: `HourlyObservationBackfill:309` already filters
`stationType == "OFFICIAL"`.

### Change

`SynopticObservationRefresher.refreshIfDue` currently does `observationDao().insertAll(rows)` with no
thinning. Thin **on write**, not with a periodic delete — a delete pass over an 80k-row table is the
same cold-read cost this is trying to avoid:

- For each PERSONAL station in the incoming batch, keep a row only when it is ≥10 min after the last
  row already stored (or already kept from this batch) for that station.
- OFFICIAL rows pass through untouched.
- Keep the newest row of every station unconditionally, whatever its spacing — staleness of the
  latest reading drives `DOMINANT_STATION`, the `readingAgeMin` badge and the backfill's
  `latest_gap_min` gate, and must not regress.

### Risks

- The actual series is **event-sampled** (see the `actual_series_event_sampled` note): fewer stored
  timestamps means fewer emitted points. Mitigated by these stations being 0.05-weighted while the
  curve is driven by KSJC (4.6 min, official) and KNUQ.
- Daily extremes come from the blended series, so a brief peak seen only by one personal station
  could be missed. At 0.05 weight it moves the blend by a fraction of a degree.
- Retention already prunes at ~10 days, so this compounds rather than conflicts.

### Verification

- Unit: a thinning test over a synthetic batch — PERSONAL rows closer than 10 min are dropped,
  OFFICIAL rows never are, each station's newest row always survives.
- On device: `SYNOPTIC_OBS_STORED rows=` should roughly halve for the affected stations; total
  `observations` count should fall ~19% over a retention cycle. Compare `DAILY_HISTORY_STABLE`
  high/low values before and after for the same days — they should not move.

---

## Part 2 — skip recomputing days that cannot have changed

### Why (this is the big one)

`DAILY_HISTORY_STABLE` is logged **after** a full recompute, when the result turned out identical to
what was already stored. Over three days:

| outcome | count |
|---|---:|
| `DAILY_HISTORY_STABLE` (recomputed, unchanged) | **6,805** |
| `DAILY_HISTORY_OVERWRITE` (actually changed) | 663 |

**91% of per-day-per-source recomputes are no-ops** — the day is queried, blended and reduced to
extremes, and only then discovered to be identical. Same shape as the diagnostics tax the first plan
removed: the work happens, then proves unnecessary.

Change rate collapses with age:

| date | stable | overwrite | change rate |
|---|---:|---:|---:|
| 09-06 (today) | 410 | 97 | 19% |
| 09-05 | 2,096 | 239 | 10% |
| 09-04 | 2,457 | 278 | 10% |
| 09-03 | 1,267 | 49 | 4% |
| 09-02 | 465 | **0** | **0%** |
| 09-01 | 110 | **0** | **0%** |

The main sync's range is already narrow — `WidgetDataBundleLoader.fetchDailyActuals` recomputes
`today-2 .. today-1`, two days — so this is not about shortening the window. It is that **both of
those days are ~90% unchanged**, and the app cannot tell without doing the work.

### Change

A day's stored extremes can only move if an observation for that day was written after they were
computed. `daily_history` already carries `updatedAt`, so the guard is one cheap aggregate:

```
skip day D when  MAX(observations.fetchedAt for D, this location) <= daily_history.updatedAt for D
```

placed at the top of `DailyActualsStore.recomputeDailyExtremesForDay`, before the context query.

**The comparison errs safe by construction.** `fetchedAt` is the *last write attempt*, refreshed even
when a fetch stored nothing (`touchLatestFetchedAt`; see the `observations_fetchedat_attempt_semantics`
note). So it over-reports change and the guard recomputes a day it did not need to — never the
reverse. That asymmetry is why `fetchedAt` is the right column here despite being unreliable for
dating a row's *first* arrival.

Emit `DAILY_RECOMPUTE_SKIP date=… newestObs=… updatedAt=…` so the skip rate is measurable, and keep
`DAILY_RECOMPUTE_PERF` for the days that still run.

### Risks

- A location change rewrites the same day under new coordinates. The guard is per (day, location),
  matching how `persistExtremes` already keys rows, so a new site has no `updatedAt` and recomputes.
- `ForecastHistoryActivity:531` and the backfillers call the recompute deliberately to repair data.
  Give the guard an `force` escape so repair paths keep their current behaviour.

### Verification

- Unit: seed a day, recompute, assert the second recompute skips; touch an observation, assert it
  runs again; assert `force = true` always runs.
- On device: `DAILY_RECOMPUTE_SKIP` should outnumber `DAILY_RECOMPUTE_PERF` roughly 9:1, and
  `SYNC_PERF actuals=` should fall from ~2,094 ms toward the cost of today alone.

---

## Part 3 — prefer screen-off for what still recomputes

### Why

179 full syncs (`recompute = !uiOnlyRefresh`, so these are the ones that do the work) over three days:

- **98 (55%) ran with `interactive=true`** — screen on, user present.
- 81 (45%) ran screen-off.

The recompute is already **off the tap path directly** — a UI-only refresh passes `recompute = false`.
The problem is that *paints enqueue full syncs*: observed trigger reasons include
`cloud_while_viewing`, `on_update_stale`, `missing_actuals_NWS_today` and `missing_today_snapshot_NWS`.
So the heavy work is scheduled by the very act of looking at the widget.

### Change

Deliberately **third**, and deliberately small: Parts 1 and 2 remove ~90% of this work, and
scheduling only moves cost. Once they land, defer the residual multi-day recompute (never today's) to
a screen-off or idle moment, reusing `WidgetPaintCoordinator.isScreenInteractive()`, and let it ride
the next sync that finds the screen off.

### Risk

The device that is never plugged in and never opened is the one whose periodic tick is already the
only thing resampling location (see CLAUDE.md's fetch-interval table). Deferring must never mean
"never": bound the deferral so a day that has waited too long recomputes regardless of screen state.

---

## Order and expected effect

1. **Part 2 first** — largest win, removes work rather than moving it, and is a guard rather than a
   change to what gets computed.
2. **Part 1** — shrinks the table, which is what cold reads are actually bottlenecked on.
3. **Part 3** — only worth measuring once 1 and 2 have shrunk the residual.

Land and measure each separately so attribution stays clean, as with the previous two plans.

## Not doing

- **Changing the Synoptic station limit.** Measured and rejected: `DEFAULT_LIMIT = 10` plus the
  `SkyReportingStationSlots.MIN_SKY_STATIONS = 3` top-up yields 11 (only KNUQ and KPAO among the
  nearest ten report sky, so one more — KSJC at 10 km — is added). Lowering it to 8 yields **9**, not
  8, saves ~5%, and is fragile: KPAO sits exactly at rank 8, so a slightly different radius set makes
  the shortfall 2 and the total 10. `SkyReportingStationSlots`' own KDoc already argues the limit is
  the wrong axis; that cuts both ways.
- **Dropping the duplicate `src=SYNOPTIC` daily_history row.** It duplicates `src=SILURIAN` for every
  settled day (verified identical; only today's in-progress row differed, low 53.38 vs 51.22). Part 2
  makes it nearly free, so it is not worth the risk of removing a row something may read.


---

# Outcome

Parts 1 and 2 implemented and measured on SM-F936U1. Part 3 was gated on these measurements and is
**not** built; the recommendation is now to skip it.

## Part 2 — the first design was wrong, and only the device showed it

The plan proposed `MAX(observations.fetchedAt) <= daily_history.updatedAt`, reasoning that `fetchedAt`
over-reports change and therefore "errs safe". It does err safe — **on essentially every row, every
sync**, so the guard never fired once. Shipped, measured, zero `DAILY_RECOMPUTE_SKIP` rows.

On a *fully settled* day (09-04):

| | value |
|---|---|
| newest `fetchedAt` | **the current minute** |
| oldest `updatedAt` | 2026-09-04 16:41 |
| newest observation `timestamp` | 2026-09-04 23:58 (correctly frozen) |

**7,315 of that day's 7,633 rows carried a `fetchedAt` more than an hour after their own
`timestamp`** — observations are written `INSERT OR REPLACE`, so every deep fetch re-stamps rows it
already holds, and `touchLatestFetchedAt` bumps the stamp even for a fetch that stored nothing. The
column records "we looked", not "something changed". "Errs safe" and "never fires" are the same
behaviour at a 100% error rate; the plan asked the first question and not the second.

**Replaced with a content signature** — `COUNT(*)`, `MAX(timestamp)`, and integer sums of
temperature, precip, cloud and the QC flag — held in a process-local map keyed by (day, quantized
location). One indexed aggregate, no rows materialized, no schema change; losing it on process death
costs exactly one recompute.

A second defect surfaced from the existing suite, not from anything new: a temperature-only
signature dropped **measured precip**, which arrives by REPLACE on an existing key with count,
newest timestamp and temperature all unchanged.
`ObservationRepositoryDailyMergeTest > recompute persists precip-only change for a past day` caught
it on the first full run. Hence the rule now written on the query: **a column the daily reduction
consumes must be in the signature.**

### Measured

| `SYNC_PERF` (full sync) | recompute | total |
|---|---:|---:|
| data changed | 2,531–5,083 ms | 10,544–17,654 ms |
| **nothing changed (guard hits)** | **273 ms** | **5,365 ms** |

Against the 52,162 ms this thread started at, a settled full sync is now **5,365 ms**.

## Part 1 — thinning works, with one honest caveat

`SYNOPTIC_OBS_STORED reason=full_sync hours=24 rows=1441 fetched=1998 thinned=557` — **28% of each
Synoptic batch dropped**, stable across runs, every station still present including KNUQ.

**Caveat: the live edge stays denser than 10 minutes.** Each batch keeps its *own* newest row per
station (deliberately — latest-reading staleness drives `DOMINANT_STATION`, `readingAgeMin` and the
backfill's `latest_gap_min`), and fetches run every few minutes, so successive fetches each
contribute one trailing row. History thins as intended; the last hour does not. Defensible — recent
data is what the graph draws at full resolution — but if more saving is wanted the exemption would
have to become "this station's newest row in the database" rather than "in this batch", which costs a
query per station on the write path.

## Part 3 — recommend not doing it

Its premise was that 55% of heavy recomputes run with `interactive=true`. Part 2 has since taken a
settled recompute to **273 ms**, so what would be deferred is no longer heavy, and deferral carries a
real risk: on a device never plugged in and never opened, the periodic tick is the only thing that
resamples location, so "defer" must never become "never". Not worth that for 273 ms. Revisit only if
`DAILY_RECOMPUTE_PERF` shows the residual growing.

## Verification

- `DailyRecomputeSkipQueriesTest` — 7 cases, including the exact defect that killed the first design
  (re-fetching the same rows with new stamps must NOT change the signature) and the precip
  regression.
- `PersonalStationThinningTest` — 11 cases: official stations never thinned however dense, five-minute
  personal halved, ten- and fifteen-minute untouched, newest row always kept, stations bucketed
  independently, overlapping fetches converge, unknown station types treated as official.
- `WeatherApiHistoryBackfillerTest` needed `force = true` in its verify — the backfillers are repair
  paths and must recompute even when the signature says settled. That the test failed is the point:
  it caught the behaviour change on a path the guard would otherwise have silently disarmed.
- Full `:app:testDebugUnitTest` and `:shared:test` green.
