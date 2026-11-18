# Cold start: take the daily actuals blend out of the first paint, and shrink it

**Date:** 2026-11-18
**Status:** approved 2026-11-18 ("do it"); Parts 1–3 implemented and measured; stored-today rule decided (option A).
**Scope:** `:shared` (`ActualsAggregator`, `DailyActualsAssembler`, `ObservedPrecip`), Android
startup paint (`WidgetStartupCoordinator`, `DailyActualsStore`), desktop (shared change only)

Follows `performance/261115-phase3-targeted-refresh-and-daily-blend-cost.md`. Its B2 is done, and
the two B3 candidates it listed are Parts 1 and 2 here. Everything below is a Claude proposal
unless marked as a user decision.

## Report

"pixel 7 pro: slow to start after install. Wondering what we can do to improve performance. Are
there too many hourly rows in the DB?" (user, 2026-11-18)

## Evidence

Pixel 7 Pro `2A191FDH300PPW`, debug build 26111701 installed 2026-11-18 07:09:07. Three widgets:
78 and 79 show NWS, 88 shows SILURIAN. Sources: `app_logs` and `weather_database` pulled at 07:11,
plus logcat.

### The hourly rows are not the cost

| Table | Rows | Size |
|---|---|---|
| observations | 32,039 | 6.4 MB |
| hourly_forecasts | 17,637 | 2.1 MB |
| app_logs | 14,119 | 2.2 MB |
| whole DB | | 20 MB |

The hourly read took `sqlMs=165 stitchMs=235` (2,597 rows stitched to 489). The startup read's SQL
total for the daily actuals was `readMs=1550`.

### Where the 20 s first paint went

`COLD_START_PERF firstPaintAgeMs=20127 firstTriggerAgeMs=534`.

| Step (startup token `startup-244174156`) | Cost |
|---|---|
| forecasts, gap fill, snapshots | 0.1 s, 0.15 s, 0.6 s |
| hourly / current temps (run in parallel with the rest) | 1.9 s / 1.7 s |
| **daily actuals (`extremesMs`)** | **13.2 s** |
| render 79 (text), 78 (graph), 88 (graph) | 4.2 s, then 78 and 88 at +4.9 s and +6.0 s |

The 8 s `CLICK_WATCHDOG` released the `onUpdate` broadcast at 07:09:17.6, while the actuals read
was still running. `performance/261115-boot-first-paint-frozen-behind-startup-syncs.md` gives the
platform limit: a process released before its first paint can be frozen. Getting the first paint
inside the held broadcast is the dependable fix.

The actuals read split (`DAILY_ACTUALS_PERF`):

```
readMs=1550 assembleMs=11673 obs=2956 pastRows=184 groups=4 blended=6 skipped=2
blendMs=4299 blendObs=4332 precipMs=3881 groupingMs=71 aggregateMs=8370
```

The same read on the warmer process 17 s later: `assembleMs=7764 blendMs=1811 precipMs=2924`.

### Four groups blended, two displayed

The display sources are NWS and SILURIAN (`activeSourceList`), but the live summary lists four:

```
live=[GOOGLE_WEATHER[blendedHigh=53.88251,blendedLow=51.6022,rows=111];
      NWS[blendedHigh=53.88251,blendedLow=51.6022,rows=111];
      OPEN_METEO[blendedHigh=53.88251,blendedLow=51.6022,rows=111];
      SILURIAN[blendedHigh=54.25327,blendedLow=51.152176,rows=459]]
```

GOOGLE_WEATHER and OPEN_METEO borrow NWS's observations and give identical results. Read from the
code: `ActualsAggregator.aggregate` builds `borrowedGroups` from
`WeatherSourceOrdering.ALL_CONFIGURABLE`, not from the caller's sources. So
`DailyActualsAssembler.assemble` filters the observations to the active sources' providers, and
the aggregator then adds a group for every configurable source that borrows one of those
providers.

Nobody reads those extra groups:

- Desktop keeps only `result.bySource[displaySource.id]` (`DesktopWeatherRepository.loadDailyActuals`).
- On Android, `DailyActualsCoverage` already treats the caller's filter as the contract: a repaint
  of a source outside the filter reloads. Past rows are filtered to `activeIds` already, so an extra
  group holds only today plus any past gaps.

### The rain step repeats per source

`resolveDailyPrecip` → `ObservedPrecip.daily(windowObs, date)` runs once per (source group, date).
A borrowed group's `obsList` is the same `byApi[providerId]` list as its provider's, so
`windowObs` is equal and the observed result is equal. Each call re-runs `byHour`: grouping by
station, the routine-minute vote, and a `MetarDecoder.decode` per raw report, memoised only within
that one call.

This hits the sync's recompute hardest. `DailyBlendRecomputeRun.day` blends every displayable
source, because accuracy grades them all. Warm `SYNC_PERF recompute=` on this phone runs
3.1–17.7 s (2026-11-17 to 11-18).

### Stored today rows already equal the live blend

`daily_history` today rows, written by the 07:04:03 recompute:

| source | high | low |
|---|---|---|
| NWS | 53.882511 | 51.602200 |
| SILURIAN | 54.253269 | 51.152176 |

These are the live read's values at 07:09:24 to the last digit. The assembler deliberately never
reads a stored today row, because it lags the observation window. That reason holds for the final
paint, not for a provisional one.

### Debug build amplifies everything

`dumpsys package dexopt`: `status=run-from-apk`, `DEBUGGABLE`. After every install the code runs
with no AOT and an empty JIT. The emulator does a past-day recompute in about 0.5 s
(`performance/261115-phase3…`). Play users get the minified, non-debuggable build, so these numbers
overstate their cold start. They are still the user's daily experience on the test phones.

## Root cause

1. The live read blends sources nobody displays: groups come from `ALL_CONFIGURABLE`, not from the
   caller's sources.
2. The observed-rain reduction is recomputed per source over identical provider rows.
3. The first paint waits for the live blend, although a stored row from the last recompute is
   available and usually identical.

## Plan

### Part 1: blend only the caller's sources (`:shared`)

`ActualsAggregator.aggregate` gains an optional `sources: Set<String>?` (null keeps today's
behaviour for the recompute). A group is built only when its id is in the set. This includes the
borrowed groups: a borrowed group is kept when the **borrowing** source is wanted, not when its
provider is.

`DailyActualsAssembler.assemble` passes `activeIds`. That covers both platforms and every Android
caller: the startup load, the worker bundle, and `DailyInteractionRenderer` taps.

Equality: for every source in `activeIds`, the returned `bySource[source]` is unchanged. Each
group's rows depend only on its own observation list and hourly forecasts.

Expected effect here: 4 groups become 2, and 6 blends become about 3. Today's
`assembleMs=11673` should fall by roughly half (estimate; measured below).

Not changed: `DailyBlendRecomputeRun` still writes rows for every displayable source, because
accuracy needs them.

### Part 2: reduce observed rain once per provider rows (`:shared`)

Inside one `aggregate` call, memoise the **observed** part of `resolveDailyPrecip` by
(observation-list identity, date). Groups that share a provider share one `ObservedPrecip.daily`.
The forecast fallback stays per source, because it reads that source's own hourly forecast.

Also share the `decoded` METAR map across the call's `byHour` runs (it is a pure function of the
raw string). Today each call starts empty.

Equality: the observed totals are a pure function of (`windowObs`, `date`, `zone`). The memo key
pins the first two, and the zone is fixed per call. The identity key (not structural equality)
means a cache hit is cheap and can never confuse two lists that merely look alike.

Expected effect: in a recompute with 7 groups over about 3 providers, rain work drops to about
3/7. On today's live read after Part 1, NWS and SILURIAN have different providers (rows 111 vs
459), so Part 2 adds little there. It mainly shortens the sync recompute.

### Part 3: provisional first paint from stored today rows (Android startup)

`WidgetStartupCoordinator.loadStartupData` paints in two passes:

1. **Provisional.** Load today from the stored `daily_history` row (same site box and
   nearest-row rule as the past rows), through a new `DailyActualsAssembler.assembleStored(…)`. It
   shares the merge, previous-site fill, and stored-past-wins rules with `assemble`, but does not
   blend. It still applies `TodayActualsCoverage`'s late-start low gate. The gate needs only today's
   provider observation timestamps, a cheap indexed read with no blend. Render all widgets from it.
2. **Live.** Run the existing `getDailyActualsWithLiveToday` after the first paint has been
   pushed. Repaint only widgets whose today actual differs from the provisional one. Equal results,
   as on 07:09 today, push nothing.

Rules (Claude proposals, for approval):

- Use the stored today row only when `updatedAt` is no older than the last observation fetch the
  row could have seen. Otherwise paint today with no actual (forecast only), as a source with no
  observations is painted now. Never substitute the forecast for the actual.
- No dimming or "provisional" marking: the window is seconds, and the values are usually equal.
- `StartupCooldown` keys on the live pass finishing, not the provisional paint, so background
  syncs still wait for the whole startup load (phase 1 of the boot plan).

Expected effect: first paint is about the remaining load (~2 s, bounded by hourly and current
temps) plus the render (4.2 s for the first widget), so about 6–7 s on this cold start. That is
inside the 8 s watchdog, from 20 s now (estimate).

Desktop: no change in Part 3. The desktop process is long-lived and its paint is not gated by a
broadcast deadline. Parts 1–2 reach desktop through `:shared`.

### Out of scope (noted)

- **Render cost.** Widget 78's graph `renderMs=4475` and `otherMs` 1.3–2.1 s per widget are the
  next-largest cold costs. They need their own measurement.
- **Baseline Profile** (`profileinstaller`) for the release build: helps Play users, not debug
  installs. Separate change.
- **Sharing the series blend across borrowed groups.** It depends on the source's own forecast;
  B3's third candidate, unproven.

## Tests

- `ActualsAggregatorWantedRowsTest` (`:shared`): with `sources = {NWS, SILURIAN}` and providers
  that GOOGLE_WEATHER / OPEN_METEO borrow present, the result has no GOOGLE/OPEN_METEO rows. NWS
  and SILURIAN rows equal the `sources = null` run field by field. This fails on the old code, which
  returns four sources.
- `ActualsAggregatorObservedRainTest`: two groups sharing a provider list call
  `ObservedPrecip.daily` once per date (counting seam), with results equal to the unmemoised path.
  Includes a wet day with a SPECI, so the routine-minute vote is exercised. A different source on
  the same provider with **no** observed rain still gets its own forecast fallback.
- `DailyActualsAssemblerTest`: `assembleStored` against `assemble` on the same rows. Today equals
  the stored row, the late-start gate nulls the same low, and past rows are identical.
- **Integration (Android, real Room via Robolectric):** `DailyActualsStore` over a seeded
  `WeatherDatabase` with NWS, METAR/SYNOPTIC, and stored today rows. Assert the provisional and
  live results for the display sources, and that only the display sources come back.
- **Integration (startup):** `WidgetStartupCoordinatorTest` with a real-DB-backed repository. The
  first render receives the stored today row before the live read completes. A second render
  happens only when the live value differs. The cooldown is released after the live pass.
- **Desktop:** an existing `DesktopWeatherRepository` actuals test is run unchanged as the
  cross-platform equality check.

## Measurement

Same Pixel, about three cold starts after `installDebug`, before and after:
`COLD_START_PERF firstPaintAgeMs`, `WIDGET_STARTUP_PERF extremesMs`, `DAILY_ACTUALS_PERF
groups/blended/precipMs`, and warm `SYNC_PERF recompute=` for Part 2. The emulator as a
cross-check. Results are recorded in this file.

## Documentation

- `arch/daily-history-extremes.md`: the live read blends only the caller's sources, the rain
  reduction is shared per provider, and the provisional stored-today paint.
- `arch/operations.md` "Slow first paint after a process start": the two-pass startup and how to
  read `extremesMs` versus the new provisional timing.
- `arch/forecast-and-observation-cadence.md:215`: link to the above, no repeated numbers.

## Results (2026-11-18, Pixel 7 Pro, debug build)

Same phone, same data, 3 widgets; "before" is the 07:09 install in Evidence.

| | Before | After (9 cold starts, 07:33–07:57) |
|---|---|---|
| First paint (`firstPaintAgeMs`) | 20,127 ms | 8,211–9,335 ms (one run under the 8 s log threshold) |
| Startup actuals read the paint waits for (`extremesMs`) | 13,233 ms | 487–832 ms (stored rows) |
| Live read groups / blends | 4 / 6 | 2 / 2 |
| Live `assembleMs` | 11,673 ms (7,764 warm) | 4,243–5,850 ms |
| `blendMs` / `precipMs` | 4,299 / 3,881 ms | 1,030–1,512 / 1,353–2,045 ms |
| Live pass after the paint (`liveExtremesMs`) | — | 4,936–7,012 ms |

Found while measuring, and fixed:

1. **The first freshness rule was wrong.** It compared a row's `updatedAt` with the provider's
   newest observation, but `DailyBlendWrite` writes nothing when a re-reduction changes nothing
   (`DAILY_HISTORY_STABLE`). The NWS row from 07:04:03 equalled the live blend to the last digit and
   was refused on every start. The recompute's skip signature was tried next and also refused it
   (see the decision below).
2. **The provisional paint forced fetches.** With no today actual it triggered
   `missing_actuals_NWS_today` (a forced NWS sync). The provisional render now makes no
   missing-actuals request (`WeatherData.actualsProvisional`). The live pass repaints widgets whose
   provisional map lacked today, so the real render asks when a request is due. None seen in the
   later 6 runs.
3. **A superseded widget paint aborted the batch** (07:40:03, `onUpdate_CANCELLED … DeferredCoroutine
   was cancelled`): a worker UI-only paint re-registered widget 88 with `WidgetUpdateTracker`, which
   cancelled the startup task, and `awaitAll` rethrew it. This predates this change. It used to skip
   the staleness check and periodic schedule, and here it also dropped the live pass.
   `continueIfSuperseded` continues when only a child was cancelled, and the caller's own
   cancellation still propagates.

### Open decision: the stored today row is almost never "current"

The recompute's signature covers every feed's observations for the day. Synoptic, NWS and
Tomorrow.io readings land every few minutes, so any reading since the last recompute fails it. In all
three final runs `reducedFromCurrent=false`: both stored rows were refused, today painted with no
actual, and the live pass repainted all three widgets 13–15 s after the first paint. Before the
install the widgets showed their previous views, which had today's actual.

Options, for the user:

- **A. Always paint the stored today row** (still shaped and gated). Real data from the last
  recompute. The live pass corrects it seconds later and repaints only when it differs.
- **B. Keep the strict rule** (current code): never show an outdated actual, but on most cold starts
  today has no actual for about 13 s.
- **C. Per-provider signatures**, so only the row's own provider's readings count. Exact, but it
  changes what the recompute stores.

**User decision 2026-11-18: A.** The freshness gate and `isReducedFromCurrent` were removed. The row is
still shaped like a live row and gated for a late-starting low.

After A (4 cold starts, 08:05–08:09): first paint 8,487–8,983 ms, and today painted from the stored
rows (NWS 53.88/51.60, SILURIAN 54.25/51.15, equal to the live highs and lows). The live pass
repainted only what changed: run 1 widget 88 alone. `STARTUP_LIVE_ACTUALS diff=` showed the other
repaints were today's **rain totals** (`precipAmountMm`, `precipDayMm`), from rain reports fetched at
08:08 after the rows' last recompute (07:42). My reinstall loop kept killing the process before a
sync could run, so this is the correction A intends, not a mismatch. No `missing_actuals` fetch and
no aborted batch in any run.

Not addressed here, and the next-largest cold costs: the graph render (`renderMs` about 4.3 s per
graph widget) and the remaining live blend (about 5–7 s, now off the first paint).

## Order

1 → 2 → measure → 3 → measure. Parts 1 and 2 are small, `:shared`-only, and provably output-equal
for displayed sources. Part 3 changes paint ordering and is where the cold-start gain is largest.
