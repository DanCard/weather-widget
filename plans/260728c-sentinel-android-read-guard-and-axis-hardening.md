# NWS `-100°F` Sentinel, Part 2: Android Read Guard + Axis Hardening

Follow-up to `260727-nws-sentinel-temp-poisons-daily-low.md`. That plan fixed ingest and added a
read guard, but wired the read guard to desktop only on the strength of a conclusion that today's
evidence disproves. The same stored row is still poisoning the Android widget.

## Evidence

1. Reported again today (2026-07-28) as "samsung display messed up", then confirmed by the user as
   also present on **emulator and Pixel**. Same shape as last time: a data-layer defect wearing a
   device-specific costume.

2. Screenshot symptom is the *axis collapse*, not a bad label. Today's column bar stretches from the
   top of the graph past the day-label row; the other nine columns are ~100px stubs. Measuring the
   screenshot: Friday's 85° bar top at y≈320 and Wednesday's 58° bar bottom at y≈420 gives ≈3.7
   px/°F, so the tall bar's ~530px span is a **~145°F range**. The y-axis is running from 85 down to
   about −100. Today's icon and its `58.0°` low label are dragged to the floor because they stack
   below that bar's bottom edge.

3. One surviving row, identical on all three devices (`forecasts`, `source='NWS'`, `lowTemp <= -50`):

   | Device | location | targetDate | dateOfPrediction | high | low |
   |---|---|---|---|---|---|
   | Samsung `SM-F936U1` | 37.422, -122.073 | 2026-07-28 | 2026-07-26 17:00 | 75.0 | **-100.0** |
   | Pixel 7 Pro `2A191FDH300PPW` | 37.417, -122.089 | 2026-07-28 | 2026-07-26 17:00 | 78.0 | **-100.0** |
   | Emulator `emulator-5554` | 37.417, -122.089 | 2026-07-28 | 2026-07-26 17:00 | 78.0 | **-100.0** |

   It is exactly one row per device — the ingest gate is working. This is purely residue.

4. **The timeline explains the survival.** The row was written at `2026-07-27 12:20:10`. The ingest
   gate (`0e12db4f`, "Filter NWS -100F sentinel from daily temperatures") landed at
   `2026-07-27 14:07` — roughly two hours *later*. The gate only ever protected rows written after
   itself, which is precisely why `orNullIfImplausibleTempF()` was written as its counterpart.

5. **The "Android was immune" conclusion was wrong.** `260727`'s second-defect section reasoned that
   `ForecastDao` "always orders `batchFetchedAt DESC` or selects `MAX(batchFetchedAt)`, never
   excluding the newest batch." That is true of the *latest-forecast* reads, but the today-column
   **snapshot** read deliberately reaches backwards, exactly like the desktop query that was found
   to be at fault. `DailySnapshotSelector.selectPriorDaySnapshot` (`:shared`) is explicit:

   ```kotlin
   val cutoff = nowMillis - PRIOR_WINDOW_HOURS * 3_600_000L   // 24h
   return candidates.filter { fetchedAt(it) < cutoff }.maxByOrNull(fetchedAt)
       ?: candidates.minByOrNull(fetchedAt)
   ```

   The bad row was fetched `07-27 12:20`; "now" at capture was `07-28 12:29`. It is just over 24h
   old, which makes it the *preferred* candidate for this selector. Android is not immune; it has
   the same structural pattern (a path that intentionally reads an older row) in a different file.

6. **The guard is wired to desktop only.** Every caller of the read-side helper:

   ```
   shared/.../data/local/desktop/DesktopWeatherDao.kt:865,866,1024,1025,1057,1058
   ```

   Six call sites, all desktop. Android's equivalent passes the value through raw at
   `DailyViewLogic.kt:400-401`:

   ```kotlin
   snapshotHigh = snapshot?.highTemp,
   snapshotLow  = snapshot?.lowTemp,
   ```

   Net effect today: the desktop app renders this correctly and the widget does not.

7. **One unlabeled value rescales the whole graph.** `computeLayout`
   (`DailyForecastGraphRenderer.kt:497-517`) folds `snapshotHigh`, `snapshotLow` and `ghostLineHigh`
   into `minTemp`/`maxTemp` alongside the displayed values. None of those three is ever printed as a
   number, so garbage there is invisible as text while still setting the axis that `tempToY` maps
   every column through. This is the mechanism behind both the 07-27 and 07-28 reports, and nothing
   at the geometry layer currently prevents it.

8. **The correct value was on-device the whole time.** NWS hourly for target 2026-07-28, from the
   Samsung DB, grouped by prediction date:

   | predicted_on | hourly_min | hourly_max | hrs |
   |---|---|---|---|
   | 2026-07-25 | 58.0 | 78.0 | 144 |
   | **2026-07-26** | **58.0** | 80.0 | 144 |
   | 2026-07-27 | 58.0 | 80.0 | 192 |
   | 2026-07-28 | 58.0 | 78.0 | 57 (partial) |

   The poisoned snapshot was predicted on **07-26**, and that same fetch's hourly series says
   **58.0** — which is exactly the low the widget prints for today. A repair would have been exact,
   and `58.0` is stable across all seven prediction dates, so the signal is not noisy.

9. **But hourly is not a precision oracle.** In the same table, hourly max for 07-26 is `80.0` while
   the stored daily high is `78.0`. That 2° gap is legitimate: NWS files a day's low against the
   night that *ends* that morning (see `RejectedNwsTemperature`'s KDoc, and `260727` item 8, where
   the originating window yields 58° and a naive narrower window yields 59°). Hourly coverage is
   also uneven — note the 57-hour partial. Any cross-check must therefore be a **wide-tolerance
   disagreement detector**, not an equality test, or it will false-positive on routine attribution
   noise.

10. A missing low is a well-supported state — `DailyViewLogic` guards on `lowTemp != null` at lines
    116, 341, 379, and `formatTemp` accepts null. Nulling a bad value remains safe (re-confirmed
    from `260727` item 9).

## Goal

Close the half of the 07-27 fix that was left undone on Android, and add a geometry-layer backstop
so that no single unlabeled value from *any* source can collapse the graph again — regardless of
whether we anticipated that particular flavour of bad data.

## Prerequisite

The working tree has an in-flight refactor of `DailyForecastGraphRenderer`
(`260728b-dailyforecastgraphrenderer-code-review.md`: new `DailyBarRenderer.kt`,
`DailyHighLabelPlanner.kt`). Two consequences:

- `DailyBarRenderer.kt` calls `abs(...)` at lines 88 and 143 but does not import `kotlin.math.abs`
  (the original renderer imports it at line 16; no local declaration exists). Its mtime is 11:45,
  five minutes after the last successful build at 11:40 — so it has not been compiled. **This must
  be fixed before anything here can be built or verified.**
- Step 2 touches `computeLayout`, which that refactor is actively rewriting. Sequence step 2 after
  the refactor settles, or apply it deliberately on top, to avoid a conflict.

## Plan

### 1. Apply the read guard on Android (the actual missing half)

Mirror `DesktopWeatherDao`: apply `Float?.orNullIfImplausibleTempF()` to `highTemp`/`lowTemp` as
they are read out of `forecasts` on Android, rather than at the single `DailyViewLogic` call site.
Pushing it to the DAO/entity boundary covers every current and future Android consumer, and matches
the placement already proven on desktop; guarding only line 400-401 fixes today's symptom while
leaving the same trap set for the next reader.

Note the intended knock-on effect: the today-snapshot candidate filter is
`.filter { it.highTemp != null && it.lowTemp != null }`. A `-100` is non-null and passes it today.
Once the guard nulls the value, the row becomes "incomplete", the filter drops it, and
`selectPriorDaySnapshot` falls back to another candidate (or returns null and the snapshot bar is
simply absent). That is the correct degradation — a missing comparison bar beats a poisoned axis.

### 2. Harden the axis against outliers (promoted from `260727`'s deferred list)

In `computeLayout`, exclude implausible values when accumulating `minTemp`/`maxTemp`, so the y-axis
range can never be set by a value that no column will print. `260727` deferred this as "the general
backstop"; two separate incidents from the same mechanism in two days is sufficient argument to
promote it. Log a permanent breadcrumb when a value is excluded from the range, so the next
occurrence is one query away rather than another screenshot investigation.

This is defence in depth, not a substitute for step 1: step 1 keeps bad data out of the values,
step 2 keeps any bad value that still gets through from spreading beyond its own cell.

### 3. Daily↔hourly cross-check as a detector

Today's repair path (`NwsDailyMapper.fillTemperatureGapsFromHourly`, called at
`NwsForecastMapper.kt:138`) only runs when `rejectedTemps` is non-empty — i.e. **only after the
absolute range gate has already fired, and only at ingest**. That leaves two gaps this step closes:

- **Plausible-but-wrong values.** `MIN_PLAUSIBLE_F = -80f` is an absolute bound; a July low of 20°F
  passes it untouched. Comparing against what the same provider said in the same fetch is a
  *relative* check, which is a far sharper instrument.
- **Stored rows.** `hourly_forecast_history` retains what was predicted as of each date (the
  `timestampToGroupPredictions` column), so a stored snapshot can be validated after the fact —
  something an ingest-only path structurally cannot do.

Implement as a wide-tolerance comparison (flag divergence beyond ~15-20°F, then repair from hourly
over the originating window per `260727` item 8). Per evidence item 9, do **not** tighten this
toward equality: ±2° disagreement is normal and correct.

### 4. Decide on the stored rows (needs an explicit call — not doing this unasked)

`260727` deferred the purge as a "destructive write to real data". Steps 1 and 2 neutralise these
rows for display, and this particular row ages out of the today-column read tomorrow. But it stays
in the 1-month retention window and would distort forecast-accuracy comparisons. Options: leave it
to expire, null just the `lowTemp` on rows failing the plausibility gate, or delete the rows
outright. **Flagging for a decision; taking no action here.**

## Automated tests

Weighted toward automated coverage rather than device verification, so a regression fails a build
rather than waiting for another screenshot.

**`:shared` JVM tests** (fast, no Android deps — the preferred layer):
- `orNullIfImplausibleTempF()` nulls `-100f`, passes `58f`, and rejects `NaN`/infinity (the KDoc
  explicitly claims NaN handling; assert it rather than trust it).
- `selectPriorDaySnapshot` given a candidate list where the >24h-old entry is the poisoned one:
  assert that once the guard has nulled it and the caller's completeness filter has dropped it, the
  selector falls back rather than returning the bad row. This is the exact interaction that failed.
- Cross-check tolerance (step 3): a 2° daily-vs-hourly divergence does **not** flag; a 158° one
  does. Encode evidence items 8 and 9 directly as the fixture.

**App unit tests** (remember: `@Category` is required on app unit tests here):
- `DailyViewLogic` end-to-end over a fixture containing the real poisoned row: assert the resulting
  `DayData.snapshotLow` is null, not `-100f`.

**Renderer tests** — assert *dp geometry, never colors* (Robolectric has no font engine, so text
measures ~0 wide, and renderer test colors come back zero):
- `computeLayout` with a `snapshotLow = -100f` present: assert `minTemp` reflects the displayed data
  (~56.8) and not `-100`, and that a normal day's bar height stays within the expected band.
- Regression-shaped assertion: build the exact ten-day fixture from this screenshot and assert no
  column's bar exceeds a sane fraction of `graphHeight`. That is the invariant that actually broke.

Prove each new test can fail before accepting it (per the standing note that a test asserting
zero-width text passes vacuously).

## Verification

1. Fix the `abs` import (Prerequisite), then `./gradlew installDebug`.
2. `./gradlew testDebugUnitTest` and the `:shared` suite.
3. On device, force a refresh and confirm from `renderGraph` logging that `minTemp` is sane
   (~56.8, not -100), then capture a screenshot on the Samsung and confirm all ten columns are
   proportionate and today's low label sits above the day-label row.
4. Confirm on the emulator and Pixel too — all three carried the row, so all three are the test.

## Outcome (implemented 2026-07-28)

Steps 1-3 are done; step 4 remains deferred pending an explicit decision.

1. **Read guard** — applied across the whole `ForecastDao` surface. Each `@Query` returning
   `ForecastEntity` was renamed `...Raw` and given a same-named wrapper applying
   `withPlausibleTemps()`, so all ~15 existing call sites are covered with no call-site edits.
2. **Axis hardening** — `computeLayout` now excludes implausible values from `minTemp`/`maxTemp`,
   with a permanent `computeLayout: excluded N implausible temp(s)` breadcrumb. Kept flat/inline
   rather than a local `fun` so the earlier per-render allocation win isn't given back to closure
   capture.
3. **Cross-check** — `NwsDailyMapper.detectHourlyDivergence` + `clearRejectedTemps`, feeding the
   existing `fillTemperatureGapsFromHourly` repair, wired into `NwsForecastMapper` behind a
   `NWS_TEMP_DIVERGED` breadcrumb. Tolerance 20°F, minimum 12 hourly readings.

### Third defect, found during verification: hardening the axis is only half the job

The first run of the new renderer test failed, and correctly. Excluding the sentinel from the axis
*range* stops it dragging the other nine columns, but the poisoned column's own bar is still drawn
at `tempToY(-100)` — measured at **1585px on a 400px canvas**, straight through the icon row, the
low label and the day-label band. Range exclusion and containment are two different properties.

Fix: `tempToY` clamps into `[graphTop, graphBottom]`. This is an identity for every value that set
the range (min/max are accumulated from the same seven fields every caller passes in), so it only
bites values `computeLayout` deliberately excluded. Verified against all `tempToY` call sites first
— every argument traces back to those seven fields or a resolver over them.

Consequence worth knowing: a poisoned bar now renders at full graph height rather than off-screen.
That is acceptable — it is confined to its own cell, and step 1 is what stops the value existing.

### Verification

- `:shared:test` and `:app:testDebugUnitTest` both green (full suites, no regressions from the
  `tempToY` clamp).
- Every new test was mutation-checked rather than merely observed passing. Each binds to exactly one
  guard: tightening the tolerance to 1°F fails the routine-disagreement test; raising the minimum
  hour count to 999 fails only the detection tests; widening the plausibility bounds fails only the
  "other columns are inert" test; removing the `tempToY` clamp fails only the containment test;
  un-wiring a single DAO wrapper fails the DAO test *and names the offending path*.
- On-device (Samsung SM-F936U1): all ten columns proportionate, today's low label back above the
  day-label row. The snapshot bar falls back to another candidate exactly as designed.
- A live NWS fetch ran through the new ingest path at 13:10:04 and logged **zero**
  `NWS_TEMP_DIVERGED` rows — the cross-check does not false-positive on real data, which is the
  failure mode evidence item 9 warned about.

## Non-goals

- Changing the ingest gate or its bounds. It is working; exactly one row survived it, and only
  because it predates the gate.
- Reconciling daily and hourly to within a degree. Evidence item 9 shows that gap is legitimate.
- The stored-row purge (step 4) without an explicit decision.
