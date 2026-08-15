# Samsung: draw the hourly `60.9°` actual-low label under its trough

**Date:** 2026-08-15 (follow-up to `summaries/260815-samsung-actual-low-label-drawn-on-observed-line.md`)
**Device:** Samsung Fold (SM-F936U1), hourly graph, widget id **345** (`widthPx=567`, `heightPx=397`)
**Request:** "60.9 low label for actual temp graph: what do you think about trying to get that label
to draw underneath the graph?" — i.e. below its own trough, in the empty band above the forecast
line. (Not below the *plot area*; that is the hour-axis footer, where a stray `50°` once read as
`50a`.)

## Problem

This morning's fix stopped the label being stamped *across* the pink observed line, but it stayed
**above** the trough — wedged in the crook between the descending pink leg and the rise to `62°`,
its digits grazing the descent and crowding `62°`'s stem, while a wide clear band sat unused
directly beneath the trough.

## Root cause: two pre-passes, both forcing a side on a hypothetical

Both run before any geometry exists and both decide direction from values plus **array-index**
proximity.

### 1. `computeForcedAboveLowIndices` — lifted the warmer low over a cooler neighbour

Fires whenever a strictly-cooler low label sits within `NEARBY_LABEL_WINDOW` (4) indices. It never
asks whether the two labels would actually contend.

They cannot invert when both sit below their own curves: `tempToY` is monotonic, so the cooler low
always anchors lower, and equal gaps preserve that. Inversion is only reachable when the two
below-boxes overlap and the de-collision cascade shifts one. Here the pair was ~48px apart in y
(`ACTUAL_LOW anchorY=253.7` vs `LOW anchorY=302.3`) against a 30.2px label — three label-heights of
clearance, nothing contending.

### 2. `computeLeftEdgeStartOrdering` — the rule actually holding *this* scene above

Device logs settled it. Two renders, two different mechanisms:

```
# render A (no START label accepted) — the gate above already works
ExactFitPreCheck: role=ACTUAL_LOW idx=4 placeAbove=false ...   <- below tried FIRST

# render B (the reported scene) — no PreCheck at all: a forced placer claimed it
LabelAccepted: displayed="60.9" role=ACTUAL_LOW idx=7
LabelAccepted: displayed="59"   role=LOW        idx=8
PlaceAccept: role=START idx=0 ...
PlaceAccept: role=ACTUAL_LOW idx=7 step=0 above=true
```

Render B is the only one that accepted a `START` label, and `computeLeftEdgeStartOrdering` pairs
START with the nearest actual extreme within `LEFT_EDGE_START_WINDOW` (8 indices), ordering them
warmer-above/cooler-below — `60.9 > 60`, so up it went. The `forceAbove` branch is explicitly
skipped for labels in `leftEdgeOrder`, which is why no curve-fit pass ran.

**This morning's diagnosis named rule 1 because that render had no START.** Both were real; only
rule 2 governs the reported scene.

## Changes (`:shared`, so desktop inherits)

| File | Change |
|------|--------|
| `TemperatureLabelEngine.kt` | New `belowSlotsContend(warmerAnchorY, coolerAnchorY, labelHeight, belowGapPx)`. `computeForcedAboveLowIndices` now takes `tempToY`/`labelHeight`/`belowGapPx` and only forces the flip when the two below-boxes could actually overlap. The metrics/`gapDp` block moved above the call site to supply them. |
| `TemperatureLabelEngine.kt` | `computeLeftEdgeStartOrdering` now assigns a **common** side rather than opposite sides: the ACTUAL label keeps its natural side (low → below, high → above, else `prefersAbovePlacement`) and START joins it. Same reading order, without dragging the low off its trough. |

Opposite sides and a common side both produce correct order; only the second leaves each label on
the side its own curve implies.

## Tests

| Test | Change |
|------|--------|
| `TemperatureActualLowOwnCurveGrazeTest` · `actual low flipped above by a cooler neighbour…` | Re-fixtured. Its 67.4-vs-65 lows are ~31px apart, so the new gate (correctly) declines to flip them. Now 67.4 vs 66.4 with surrounding highs widening the range to ~10px/°, putting the anchors ~10px apart — genuinely contending. |
| same file · `actual low still flips above when the forecast curve dips below the valley` | **Renamed** to `actual low hugs tight below its trough when the forecast crosses the below-box`. It asserted a flip the engine does not perform, and its fixture never reached the path it documented: the forecast sat 6-7° (~65px) under a 12px box, so it was passing on the ordering rule, duplicating the test above. Since 2026-06-14 a blocked below routes to the tight below-trough hug (`reason=belowActualCurve`). New fixture crosses the box; assertions pin the hug. |
| same file · NEW `actual low stays below when the cooler neighbour is too far below to contend` | Regression test for change 1. **Verified failing** against the pre-change source (gate stubbed to `true`). |
| `TemperatureLeftEdgeStartOrderTest` | Assertions re-aimed from the old remedy to the contract. `baselineY` ordering (the actual requirement) was already passing unchanged; the `placedAbove` pair now pins the low's natural side. |

## Verification

- `:shared` suite **813 tests, 0 failures**; `:app` and `:desktop` compile clean.
- On device: `./gradlew installDebug`, nav-arrow round-trip to force a repaint. Logs show
  `PlaceAccept: role=ACTUAL_LOW idx=7 step=0 above=false leader=false` alongside
  `role=START idx=0 above=false`. Screenshot: `60.9°` sits in the clear band under its trough, off
  both curves, no longer crowding `62°`; order still reads 60.9 → 60 → 59 down the canvas.

## Gotcha for next time

The engine's `Log.v` breadcrumbs reach logcat only via the lambda-form `isLoggable` gate:

```bash
adb shell setprop log.tag.TempLabelEngine VERBOSE   # also TempLabelResolver, CurveFitPlacer
```

They are VERBOSE, so they are never persisted to `app_logs` — logcat is the only place to read
them. Also confirm the widget id from `WIDGET_RENDER_PERF` / `HOURLY_DAY_EXTREMA` in `app_logs`
before broadcasting `ACTION_REFRESH`: the hourly widget here is **345**, and refreshes aimed at the
stale 352 from the morning's notes silently did nothing.
