# NWS `-100°F` sentinel, part 2: Android read guard + axis hardening

**Date:** 2026-07-28
**Plan:** `plans/260728c-sentinel-android-read-guard-and-axis-hardening.md`
**Follows:** `summaries/260727-nws-sentinel-temp-poisons-daily-low.md`

## Outcome

Reported again as "samsung display messed up", then confirmed by the user on emulator and Pixel too.
Same row, same shape as the day before: today's column stretched from the top of the graph past the
day-label band while the other nine were ~100px stubs, every printed label reading perfectly healthy.

Root cause was not the in-flight `DailyForecastGraphRenderer` refactor sitting in the working tree —
it was one surviving poisoned row plus a guard that had been wired to only one of the two platforms
that needed it. All four surfaces now render correctly, and the widget was verified on-device.

## Evidence

One row, identical on all three devices (`forecasts`, `source='NWS'`):

| Device | location | targetDate | dateOfPrediction | high | low |
|---|---|---|---|---|---|
| Samsung `SM-F936U1` | 37.422, -122.073 | 2026-07-28 | 2026-07-26 17:00 | 75.0 | **-100.0** |
| Pixel 7 Pro | 37.417, -122.089 | 2026-07-28 | 2026-07-26 17:00 | 78.0 | **-100.0** |
| Emulator | 37.417, -122.089 | 2026-07-28 | 2026-07-26 17:00 | 78.0 | **-100.0** |

**The timeline explains the survival.** The row was written `2026-07-27 12:20:10`; the ingest gate
(`0e12db4f`) landed `2026-07-27 14:07` — about two hours later. The gate only ever protected rows
written after itself, which is exactly why `orNullIfImplausibleTempF()` was written as its
counterpart.

**The correct value was on-device the whole time.** NWS hourly for target 2026-07-28, from the same
07-26 fetch that produced the poisoned daily row, reported min **58.0°F** — which is precisely the
low the widget prints for today. Stable across all seven prediction dates.

## The wrong conclusion this corrects

`260727` reasoned that only the desktop needed a read guard, because Android's `ForecastDao` "always
orders `batchFetchedAt DESC` or selects `MAX(batchFetchedAt)`, never excluding the newest batch."

That is true of the latest-forecast reads. It is **not** true of the today-column snapshot, which
deliberately reaches backwards — the same structural pattern as the desktop query that had just been
found at fault, in a different file:

```kotlin
// DailySnapshotSelector.selectPriorDaySnapshot
val cutoff = nowMillis - PRIOR_WINDOW_HOURS * 3_600_000L   // 24h
return candidates.filter { fetchedAt(it) < cutoff }.maxByOrNull(fetchedAt)
```

The bad row was fetched `07-27 12:20`; capture time was `07-28 12:29`. Just over 24h old — which made
it the selector's *preferred* candidate. Its `-100` is non-null, so it also sailed through the
caller's `highTemp != null && lowTemp != null` completeness filter.

Generalisable lesson: the question is not *"does the DAO order by newest?"* but ***"which read paths
deliberately reach backwards?"*** — snapshot overlays, accuracy comparisons and forecast-evolution
views all do, by design. And an ingest gate can never heal rows written before it, so an ingest fix
always needs a read-side counterpart for the full retention window.

## What changed

**1. Read guard across the whole `ForecastDao` surface.** Each `@Query` returning `ForecastEntity`
renamed to `...Raw`, with a same-named wrapper applying `withPlausibleTemps()`. ~15 existing call
sites covered with no call-site edits. Guarding only the snapshot query would have rebuilt the exact
trap for the next backward-reaching reader.

Note `highTemp IS NOT NULL` in several queries is **not** a substitute — a `-100` is perfectly
non-null and passes untouched.

Intended knock-on: once the low is nulled the row fails the completeness filter, so
`selectPriorDaySnapshot` falls back to another candidate (or the snapshot bar is simply absent). A
missing comparison bar beats a poisoned axis.

Side benefit at `ForecastRepository.kt:1028`: that read feeds write-side change detection, so a
stored sentinel now reads as "different from" a good new value and the next fetch overwrites it —
the rows self-heal.

**2. Axis hardening.** `computeLayout` excludes implausible values from `minTemp`/`maxTemp`, behind a
permanent `computeLayout: excluded N implausible temp(s)` breadcrumb. `snapshotHigh`/`snapshotLow`/
`ghostLineHigh` set the axis but are never printed, so a bad value there is invisible as a number
while rescaling every column through `tempToY`. Kept flat/inline rather than a local `fun` so the
recent per-render allocation win isn't given back to closure capture.

**3. Daily↔hourly cross-check.** `NwsDailyMapper.detectHourlyDivergence` + `clearRejectedTemps`,
feeding the existing `fillTemperatureGapsFromHourly` repair, wired into `NwsForecastMapper` behind a
`NWS_TEMP_DIVERGED` breadcrumb. Tolerance **20°F**, minimum **12** hourly readings.

The existing repair only ran when the *absolute* range gate fired, and only at ingest. This is the
*relative* counterpart: a July low of 20°F is badly wrong but sits comfortably inside `-80..140`.
Deliberately wide, because the two series legitimately disagree — observed 2026-07-28, daily high
78°F against a calendar-day hourly max of 80°F (NWS files a day's low against the night *ending*
that morning, and endpoint rounding differs). A sentinel diverges by ~140°F; the threshold lives in
the gap between those magnitudes. The minimum-hours floor exists because a short partial day (57
rows in the capture) has an unrepresentative min.

## Third defect, found during verification: hardening the axis is only half the job

The first run of the new renderer test failed, and correctly. Excluding the sentinel from the axis
*range* stops it dragging the other nine columns, but the poisoned column's own bar is still drawn at
`tempToY(-100)` — measured at **1585px on a 400px canvas**, straight through the icon row, the low
label and the day-label band. Range exclusion and containment are two different properties.

Fix: `tempToY` clamps into `[graphTop, graphBottom]`. Identity for every value that set the range
(min/max accumulate from the same seven fields every caller passes in), so it bites only what
`computeLayout` deliberately excluded. Verified against all `tempToY` call sites first — every
argument traces back to those seven fields or a resolver over them.

Consequence worth knowing: a poisoned bar now renders at full graph height rather than off-screen.
Acceptable — it is confined to its own cell, and the read guard is what stops the value existing.

## Files changed

| File | Change |
|---|---|
| `app/.../data/local/ForecastDao.kt` | `...Raw` + guarded wrappers; `withPlausibleTemps()` helpers |
| `app/.../widget/DailyForecastGraphRenderer.kt` | axis exclusion + breadcrumb; `tempToY` clamp |
| `app/.../data/repository/NwsForecastMapper.kt` | cross-check wiring + `NWS_TEMP_DIVERGED` |
| `shared/.../data/remote/NwsDailyMapper.kt` | `detectHourlyDivergence`, `clearRejectedTemps` |

310 insertions across 4 files, plus 3 new test files.

## Regression coverage

- `shared/.../NwsHourlyDivergenceTest.kt` — read guard incl. NaN/infinity; the
  selector-plus-completeness-filter interaction that actually leaked; tolerance behaviour both ways;
  partial-day skip; clear-then-repair round trip.
- `app/.../data/local/ForecastDaoPlausibilityTest.kt` — real Room round-trip asserting **every** read
  path individually. Exists specifically to catch a mis-wired wrapper, which shared-module tests
  structurally cannot see.
- `app/.../widget/DailyForecastGraphAxisOutlierTest.kt` — the ten-column fixture from the screenshot.
  Asserts containment, that a sentinel is *inert* (moves no other bar), and that legitimately cold
  weather still sets the axis. **dp geometry only** — Robolectric has no font engine and renderer
  test colors come back zero.

## Verification

- `:shared:test` and `:app:testDebugUnitTest` full suites green; the `tempToY` clamp caused no
  regressions.
- Every new test mutation-checked rather than merely observed passing, each binding to one guard:

  | Mutation | What failed |
  |---|---|
  | Tolerance 20°F → 1°F | routine-disagreement test |
  | Min hours 12 → 999 | detection tests only |
  | Plausibility bounds widened | "other columns inert" only |
  | `tempToY` clamp removed | containment only |
  | One DAO wrapper un-wired | DAO test — and it named the offending path |

- On-device (Samsung SM-F936U1): all ten columns proportionate, today's `58.0°` back above the
  day-label band, snapshot bar falling back as designed. Sun/Mon now show their second (`77°`) high
  label — the dual actual-vs-forecast labels, previously colliding in the squashed axis.
- A live NWS fetch ran through the new ingest path at 13:10:04 and logged **zero**
  `NWS_TEMP_DIVERGED` rows — the cross-check does not false-positive on real data.

## Notes

- Logcat on the Samsung did not surface app-level tags this session; `app_logs` in the DB and the
  screenshot were the usable verification channels.
- The poisoned row's `targetDate` is 2026-07-28, so it ages out of the 24h snapshot window on its
  own. The fix matters for the next occurrence, not this one.

## Deferred

- **Purging the stored `-100` rows** (step 4 of the plan). Neutralised for display by the read guard,
  but still in the 1-month retention window and would distort forecast-accuracy comparisons. A
  destructive write to real data — left for an explicit decision.
