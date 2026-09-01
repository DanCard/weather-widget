# Desktop draws "Actual" rain in the future, because rain-period selection is duplicated

**Date:** 2026-09-01 · **Platform:** desktop only (confirmed: not reproducible on emulator or Samsung)
**Symptom:** hourly rain graph, source Silurian, window 5a–8a with NOW at ~7:15a. Two orange
`Actual: .003in` labels — one at ~6a (past) and **one at ~8:15a, entirely in the future**.

## Root cause

Two independent defects in `desktop/.../PrecipitationGraph.kt`, both consequences of desktop having
its own hand-written copy of logic Android already had:

```kotlin
val forecastRainPeriods = selectDayNightSegments(points, emptyList())
    .mapNotNull { it.toRainPeriod(points, stepWidth) { f -> f.precipAmountMm } }
val actualRainPeriods = selectDayNightSegments(points, actualPrecipRowsForSource(observations, displaySourceId))
    .mapNotNull { it.toRainPeriod(points, stepWidth) { f -> f.precipAmountMm } }   // <-- forecast field
```

1. **The "Actual" number is the forecast number.** Both `toRainPeriod` calls read
   `points[i].precipAmountMm` — the forecast hourly rows. The observation list only influences which
   day/night segment ranks wettest (`combinedTotal`); it never contributes the value that is drawn.
   So `Actual: X` and the plain forecast `X` are always the same number, which is exactly what the
   screenshot shows (`.003in` twice, at both anchors).
2. **No now-gate.** `selectDayNightSegments` picks the wettest day run and the wettest night run
   across the whole window regardless of NOW. With a 5a–8a+ window the night run (5a–7a) and the day
   run (8a+, all future) are both selected, so an "actual" label is anchored in the future.

Silurian makes it maximally visible: `SILURIAN.historicalDataKind = NONE`, so
`actualPrecipRowsForSource` returns `emptyList()` immediately, and the desktop DB confirms **zero
SILURIAN observation rows exist at all**:

```
api           n   withPrecip
OPEN_METEO   95   94
TOMORROW_IO  61   24
NWS         631    0
METAR       167    0
(SILURIAN — no rows)
```

With an empty observation list the two calls above are literally identical, so the actual periods
are a verbatim copy of the forecast periods. The bug is not Silurian-specific — the value is
forecast-derived for **every** source — Silurian just removes the last thing that made the two
labels look different.

## Why Android is clean

`PrecipViewHandler.kt:595` builds each hour with a real actual field, now-gated:

```kotlin
actualPrecipAmountMm = if (currentHour.isBefore(now)) actualPrecipByHour[currentHour] else null
```

and `PrecipitationGraphRenderer.kt:313` sums *that* field for the actual periods. Future hours carry
`null`, the segment total is 0, `toRainPeriod` returns null, and no future label is produced. Android
has both guards; desktop has neither.

The two platforms hold near-identical copies of `dayNightRuns` / `selectDayNightSegments` /
`toRainPeriod` / `perHourRainPeriods` / `RainPeriod` / `DayNightSegment`. Desktop's copy is the one
that drifted. This is what the shared-module rule exists to prevent.

## What will change

### New `shared/graph/RainPeriodSelection.kt` (pure, no platform types)

- `RainHour(dateTime, precipAmountMm, actualPrecipAmountMm, label)` — the row both platforms map to.
- `RainPeriod`, `DayNightSegment` (+ `centerX`, `toRainPeriod`).
- `dayNightRuns`, `selectDayNightSegments`, `perHourRainPeriods`,
  `findVisibleWindowRainPeriods`, `findFixedWindowRainPeriods`.
- **`selectPeriods(hours, mode, hourWidth, windowHours): RainPeriods`** returning
  `forecast` and `actual` lists together. One entry point, so neither platform can pick the wrong
  field for the actual series again — the defect being fixed is precisely "a caller passed the
  forecast accessor twice", and a two-accessor API invites it back.

### `DayNightHours` moves `app/util` → `:shared`

Desktop currently hand-rolls `ldt.hour in 8 until 20`. Three Android call sites
(`PrecipitationGraphRenderer`, `RainAccuracyCalculator`) update to the shared import.

### Android `PrecipitationGraphRenderer`

Deletes its private copies; `PrecipHourData` maps to `RainHour`. Behaviour must not change — it is
the reference implementation here.

### Desktop `PrecipitationGraph`

Builds `RainHour` rows: forecast amount from `points`, **actual amount from the observation rows
matched by hour and null for any hour at/after `setup.now`**, then calls the shared `selectPeriods`.
Deletes its private copies of all six functions.

## Tests

| # | Test | Kind | Asserts |
|---|---|---|---|
| 1 | `future hours never produce an actual period` | unit | **the bug**: actual amounts null after now ⇒ no actual period, while the forecast period is still returned |
| 2 | `actual period totals the actual field, not the forecast field` | unit | **the bug**: hours with forecast=5mm, actual=1mm ⇒ actual total 1mm |
| 3 | `day and night runs split at 8a and 8p` | unit | segment boundaries |
| 4 | `wettest day and wettest night are both selected` | unit | two anchors on a busy window |
| 5 | `segment ranking counts forecast plus actual` | unit | preserves existing `combinedTotal` behaviour |
| 6 | `no rain produces no period` | unit | total 0 ⇒ null, both series |
| 7 | `per-hour mode caps at the column limit` | unit | parity with Android `PER_HOUR_MAX_COLUMNS` |
| 8 | `fixed and visible window periods pick the wettest run` | unit | window modes |
| 9 | `desktop row builder nulls actuals at and after now` | integration | desktop mapper + shared selector: the seam that was missing |
| 10 | Existing `PrecipitationGraphRendererTest` | integration | Android behaviour unchanged after delegating to shared |

Tests 1, 2 and 9 are the regression oracles; each must be shown failing against the current desktop
behaviour.

## Verification

**Implemented 2026-09-01.**

### Scope change during implementation

User observation mid-task: *"On android it says 'Pred .01in'. On desktop doesn't have the 'Pred'
text. Prefer android."* Desktop's forecast label had an empty prefix and its actual label read
`Actual: `. Both now match Android exactly — `Pred ` and `Act `. Flagging the second half: the
`Actual: ` → `Act ` change was not explicitly requested, but "prefer android" plus a mixed
`Pred …` / `Actual: …` pair would have been worse than either convention alone.

### Tests — 16 new, all passing

```
:shared  RainPeriodSelectionTest        11 tests  PASSED
:desktop DesktopRainHourNowGateTest      5 tests  PASSED
:app     PrecipitationGraphRendererTest  unchanged, PASSED against the shared implementation
:app     testDebugUnitTest (full suite)  BUILD SUCCESSFUL
```

Regression oracles shown failing against the pre-fix desktop behaviour (actual taken from the
forecast field, no now-gate): `hours at and after now carry no actual`,
`no actual label is produced in the future`, and `an hour with no observation carries null rather
than zero` all fail. Restored, all pass.

One oracle was mis-framed on the first attempt and corrected: a day/night *segment* may legitimately
span the current hour, so `endIndex < now` is the wrong assertion. What must hold is that no period
is anchored in a **fully future** region (`startIndex >= nowIndex`) and that the total counts only
elapsed hours. Both tests now assert that, plus the total, so they cannot pass by suppressing the
actual series outright.

### On the live desktop app

Rebuilt and restarted via `scripts/buildStart-desktop.sh`. Same window as the report (Silurian,
5a–8a, NOW ≈ 7:15a):

- The two orange `Actual: .003in` labels are **gone** — correct, since Silurian has no observation
  rows at all and never had an actual to show.
- Forecast labels now read `Pred .003in`, matching Android.

The positive case — an actual label that *should* appear for elapsed hours — is covered by test
rather than by screenshot: current conditions are dry (.003in) and the sources holding precip
observations would not render a visibly different label today. `DesktopRainHourNowGateTest` asserts
a real actual period is produced with the elapsed total (1mm), so the fix is not merely suppressing
the series.

### Behaviour deliberately preserved

Segment *ranking* still counts forecast + actual combined, so an elapsed downpour keeps its anchor
even once its forecast is superseded. Only the drawn totals are per-field. Locked by
`segment ranking counts forecast plus actual`.


## Follow-up found, not fixed here

**Desktop hardcodes `Mode.DAY_NIGHT` at every zoom; Android switches to `PER_HOUR` when narrow**
(`PrecipViewHandler.kt:420-423`). This is why the same forecast reads `Pred .01in` on Android and
`Pred .003in` on a narrow desktop window: the label is a *segment* total, so a narrow window shows
only part of the 8a-8p run and prints that partial sum with the same wording. Confirmed with data —
Samsung active site day run (8a-8p) = 0.0096in -> `.01in`; desktop's visible day segment (8a-9a)
= 0.0034in -> `.003in`. User verified independently: with the same window, the amounts match, so
there is no data divergence between the platforms.

Now that mode selection is a shared enum, wiring desktop's zoom to it is a small change. Left out of
this plan deliberately: it is a behaviour change to desktop's labelling at narrow zoom, not part of
the actual-in-the-future defect.
