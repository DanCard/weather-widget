# Session summary — desktop showed "Actual" rain in the future; rain-period selection is now shared

**Date:** 2026-09-01 · **Plan:** `plans/260901-share-rain-period-selection.md`

## Reported

Desktop, Silurian, hourly rain graph: an orange `Actual: .003in` label sitting to the right of NOW.
Not reproducible on emulator or Samsung.

## Root cause

Desktop carried its own copy of logic Android already had, and the copy had two defects:

1. **The actual total summed the forecast field.** Both series called
   `toRainPeriod(points, stepWidth) { f -> f.precipAmountMm }`. Observations only influenced which
   day/night segment ranked wettest; they never supplied the drawn number. So `Actual: X` always
   equalled the forecast `X`, for every source.
2. **No now-gate.** The wettest day run and wettest night run were picked across the whole window
   regardless of NOW, so an actual label could anchor on a fully-future run.

Silurian made it maximal rather than causing it: `historicalDataKind = NONE` means
`actualPrecipRowsForSource` returns empty, and the desktop DB holds zero Silurian observation rows,
so the two calls collapsed to literally the same one.

Android was correct on both counts — `PrecipViewHandler` nulls the actual for un-elapsed hours and
the renderer sums a real `actualPrecipAmountMm`.

## What changed

- **New `shared/graph/RainPeriodSelection.kt`** — `RainHour`, `RainPeriod`, `DayNightSegment`,
  `Mode`, the day/night run logic, per-hour and window modes, and a single **`selectPeriods()`**
  returning both series together. One entry point on purpose: the defect was a caller passing the
  forecast accessor twice, and a per-series accessor API invites that back.
- **`DayNightHours` moved `app/util` → `:shared`** (desktop had hand-rolled `hour in 8 until 20`).
- **Android delegates** to the shared code; its existing tests pass unchanged, which is the parity
  evidence.
- **Desktop** builds `RainHour` rows via a new `buildRainHours()` carrying the now-gate, then calls
  the same shared entry point. Its six private copies are deleted.
- **Label text matched to Android** at the user's request: `Pred ` and `Act ` (desktop previously had
  no forecast prefix and used `Actual: `).

## Verification

16 new tests; the full Android unit suite, `:shared:test` and `:desktop:test` all pass. Three oracles
shown failing against the pre-fix desktop behaviour. Live desktop rebuilt and restarted: the false
orange labels are gone and forecast labels read `Pred .003in`.

One oracle was mis-framed first time and corrected — a day/night segment may legitimately *span* the
current hour, so the assertion is "no period anchored in a fully-future region" plus an elapsed-only
total, not "endIndex < now".

## Separate question answered

Why Android read `.01in` and desktop `.003in`: the label is a segment total and the windows differ.
Samsung active site day run (8a-8p) = 0.0096in; desktop's visible day segment (8a-9a) = 0.0034in.
User confirmed that with the same window the amounts match. See the plan's follow-up note — desktop
hardcodes `DAY_NIGHT` at all zooms while Android switches to `PER_HOUR` when narrow. Not changed here.
