# Today-column overlay: the ABOVE ceiling is now the header's measured ink, per column

**Date:** 2026-08-07
**Plan:** `plans/260807-today-overlay-header-aware-above-ceiling.md`
**Files:** `DailyForecastHeaderRenderer.kt`, `DailyForecastGraphRenderer.kt`,
`TodayColumnOverlayRenderer.kt` (app), `TodayColumnOverlayPlanner.kt` (shared),
`TodayColumnOverlayPlannerLayoutTest.kt`, `DailyLargeTodayLayoutRoboTest.kt`

## Problem

On the **Samsung fold** all three overlay rows — `-2.5 fcst`, `66.2°`, `5m` — rendered across the
forecast bars while a row's worth of empty band sat above the column. (The emulator, on the same
code, already had one row above; this was fold-specific, which is why the first pass at diagnosing
it from the emulator was wrong.)

Captured live from `adb logcat -s TodayColumnOverlay:V`:

| quantity | px |
|---|---|
| `graphTop` — 50dp of reserved header band | 51.55 |
| `aboveCeiling` = `graphTop × (1 − 0.2)` | 41.24 |
| today's `1%` rain chip (box) | 57.89 – 66.26 |
| today's `82°` high label (box) | 63.17 – 102.39 |
| **free run ABOVE** | **16.65** |
| one overlay row: box / ink | 30.19 / **≈18.5** |

Short by ~1.9 px. The run ends at the `1%` chip, not the high label: the chip is 9 px wide and dead
centre, so a 71.6 px-wide stack cannot pass it.

`HEADER_BAND_RECLAIM_FRACTION` was the cause. It let the overlay rise into a fixed fraction of the
reserved header band and fenced off the rest — a ceiling of 41.24 against a header whose ink over
that column stops at ~22. The other ~17 px was reserved for header text that isn't there.

## What changed

### 1. The ceiling is measured, not guessed

New `DailyForecastHeaderRenderer.resolveHeaderInkBottom(header, widthPx, layout, xLeft, xRight)`:
how far down the header's ink actually intrudes **within that column's x-range**. It mirrors
`drawHeader` exactly — same `labelScale`, same `upOffset`, same cursor walk through icon → temp →
delta → caption → precip, same right-cluster and date placement. Text uses real ink bounds
(`Paint.getTextBounds`) and falls back to the font box when the platform reports none, so Robolectric
— which has no font engine — reads the header as *deeper* than it draws rather than silently placing
text over it. Icons have no ink to measure and always use their full drawn box.

`TodayColumnOverlayRenderer` passes `headerInkBottom + padding` as `aboveCeiling`.
`HEADER_BAND_RECLAIM_FRACTION` survives only as the fallback when no header is measured, restored to
`0.25` (an uncommitted `0.2` had been moving the ceiling the wrong way).

### 2. The x-range is the fix, not a refinement

This shipped **full-width first**, and it was 0.3 px worse than useless: it measured 30.62 px on the
fold — the 24dp weather icon at x ≈ -6..27 — against a Today column at x 122..199. Same class of
error as the original bug: reserving space for something nowhere near the column. Scoped to the
column it measures 22.49 (the current temperature) and the row fits with ~5 px to spare.

### 3. `ABOVE` hugs the bottom of its run

`TodayColumnOverlayPlanner.layOut` now places `ABOVE` at the bottom of its run less `Input.padding`,
reversing point 3 of `276bec49` from the same morning. Top-hugging was indistinguishable while the
run was ~3 px roomier than the stack; with a measured ceiling it floats the text up under the header
instead of keeping it with the column it annotates. `BELOW` still hugs its far edge (away from both
the bars and the header); `ON_COLUMN` still centres. Shared, so desktop gets it too — desktop keeps
`aboveCeiling = graphTop`, having no header measurement of its own.

## Verification

Device-verified on both, `adb logcat -s TodayColumnOverlay:V`:

```
fold before   aboveCeiling=41.24                        [delta:ON_COLUMN, dominant_temp_age:ON_COLUMN]
fold full-w   aboveCeiling=39.72  headerInkBottom=30.62 [delta:ON_COLUMN, dominant_temp_age:ON_COLUMN]
fold final    aboveCeiling=31.59  headerInkBottom=22.49 [delta:ABOVE,     dominant_temp_age:ON_COLUMN]
emulator      aboveCeiling=30.22  headerInkBottom=22.34 [delta:ABOVE,     dominant_temp_age:ON_COLUMN]
```

The emulator is the regression check and the proof that bottom-hugging works: it gained 13 px of
ceiling and its delta moved 1.8 px (box top 33.70 → 31.88).

Tests — `:shared`, `:app:testDebugUnitTest` and `:desktop` all green:

- 4 new planner cases: the fold ceiling fixture, a **paired control** at the old ceiling asserting it
  still reproduces the all-`ON_COLUMN` result (so the positive test cannot pass vacuously), an
  open-ceiling case proving the stack does not float up under the header, and one covering a ceiling
  tighter than the clearance (slack < padding must spend every pixel it has).
- 1 new Robolectric case pinning the x-scoped arithmetic: the corner icon is counted over the corner
  and *not* over a mid-widget column.
- Two existing tests updated rather than deleted, both of which asserted the old top-hugging:
  `trimmed leading hangs outside the run…` now asserts the blank descent hangs out at the bottom, and
  `an outer zone hugs the edge away from the bars` now asserts ABOVE bottom-hugs with padding.

## Follow-ups

- **Split `dominant_temp_age` into two placeable lines** so `66.2°` can join the delta above. The
  emulator now has 42.8 px of ABOVE run against 43.5 px of two-row ink — 0.7 px short.
- **Trim the high-label and rain-chip obstacles to ink.** `276bec49` taught the overlay to measure
  *itself* on ink, but obstacles are still font boxes: the `1%` chip caps the fold's run at 57.89
  with its box while its glyphs start at ≈59.4.
- Two rows above the fold's column needs ≈51.9 px against ≈33 available — not reachable without
  suppressing the `1%` chip.
