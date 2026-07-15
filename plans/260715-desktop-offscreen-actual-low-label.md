# Desktop: ACTUAL_LOW label (66.3°) detached from the actual temperature line

**Date:** 2026-07-15
**Status:** Implemented 2026-07-15 (hourDataList truncated to dataStart..dataEnd in
TemperatureGraph.kt); desktop app rebuilt and restarted; user verifying.

## Symptom

In the desktop popup, panned/zoomed to Tue 10p → Wed 1a: the pink actual line visibly
ends at the right edge around ~68°, but a pink **66.3°** label is drawn flush-right,
well below the line's endpoint. The label neither touches the line nor matches the
value the line suggests at that x.

## Root cause

The label is real data whose **anchor point is off-screen**; the label engine clamps
it back into the canvas while the line's corresponding segment is clipped away.
Three mechanisms combine:

1. **The actual series is deliberately built wider than the visible window.**
   `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt:203-204`
   passes `contextLookbackHours` / `contextLookaheadHours` with
   `ACTUALS_CONTEXT_EDGE_PAD_HOURS = 12` (lookahead floor
   `ACTUALS_CONTEXT_LOOKAHEAD_HOURS = 60`) to `ActualTemperatureSeriesBuilder.build()`
   so the pink line interpolates cleanly to the canvas corners. In this view the
   series continues ~2 h past the visible right edge — through the overnight trough
   of **66.34° at 01:50**.

2. **Extrema edge-gating uses the *series* end, not the *visible* end.**
   `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`
   refuses boundary samples as extrema (its comments name this exact zoomed/panned
   off-screen-valley scenario), but "boundary" means the first/last index of the
   dense list. The 01:50 trough is index 49 of 52 — interior to the *series*,
   off-screen in the *window* — so it passes `isActualLocalMin` and becomes an
   `ACTUAL_LOW` anchor.

3. **The label engine clamps every label's x into the canvas**
   (`geometry.clampedX` throughout `TemperatureLabelEngine.kt`). The trough's raw
   x is ~1.28× canvas width; clamping pins it flush-right (96% of width) at
   `y = yAt(66.34)`. The red line drawn from `actualLinePoints` is clipped
   geometrically by the canvas instead, so its visible end is ~68° at the right
   edge (≈1a); the descent to 66.3 happens off-canvas.

### Log evidence (autostart log, 2026-07-15 ~09:03)

```
TempExtrema:  PERDAY_RAW date=2026-07-15 ... min(idx=49 t=01:50 temp=66.33591 localMin=true
              nbr[-1]=66.6976 nbr[+1]=66.463104)
TempLabelResolver: LabelAccepted: displayed="66.3" t=01:50 role=ACTUAL_LOW reason=EXTREMA
              provenance=OBSERVED val=66.33591 idx=49
GapLabelDiag: winStartHrsBack=10 actualEndIdx=52/3 ... ACTUAL_LOW:66.3?@96% LOW:67?@97%
ActualLineDiag: ... lastActualHrsBack=6 actualPts=53 totalSeriesPts=53
```

`actualEndIdx=52/3` is the tell: the label pipeline saw a 53-point dense list while
the visible forecast list (`points`) has only 4 hourly points (10p/11p/12a/1a).
`lastActualHrsBack=6` = series ends ~3am, ~2 h past the visible right edge.

### Why desktop-only

Android's dense list never extends past its window; the context pad is a
desktop-builder feature (added so the pink line reaches the corners after zoom
extended the window range). The line and labels crop differently: line = clipped by
canvas, labels = clamped into canvas → any label anchored past the edge silently
detaches from its curve.

## Proposed fix

In `TemperatureGraph.kt` (~line 527), truncate the label pipeline's dense list to
the visible data span before feeding the engine:

- Filter `hourDataList = HourDataAssembler.assembleHourData(actualSeries, zoneId)`
  to `hd.dateTime in dataStart..dataEnd` (the span of `points`, which `xAtTime`
  maps to canvas width).
- `originalPointsList` / `forecastPointsList` stay index-aligned automatically
  since all three derive from the filtered list; recompute
  `effectiveActualEndIndex` against the filtered list (it already is — it indexes
  `hourDataList`).
- Leave `actualLinePoints` (line drawing) untouched so the curve still interpolates
  cleanly to the corner.

Effect: the 01:50 trough becomes the filtered list's edge sample, and the existing
"never treat a boundary sample as an actual extreme" gate in `TemperatureExtrema`
suppresses it — exactly the behavior that file's comments already promise for
off-screen valleys. The same truncation also protects the left edge (lookback
context is 144 h) for panned views where the series could start before the window.

### Test ideas

- Unit test on `TemperatureExtrema.compute` is already covered (boundary gate);
  the new coverage belongs at the desktop assembly layer: given an `actualSeries`
  extending past `dataEnd`, assert no ACTUAL_* label anchor maps to x > width
  (or that the dense list passed to the engine ends at `dataEnd`).
- Manual: pan the hourly view so a known overnight trough sits just past the right
  edge; verify no flush-right pink label appears and the line/label agreement holds.
