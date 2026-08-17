# Two-pass empty-space search for free-floating graph labels

## Problem

On the emulator the dominant-station label `knuq 66.2° @ 8:35 pm` landed in the bottom-left corner,
wedged between the `62°` start label and the `+0.7 from forecast` delta label, while the entire
top-right quadrant of the plot sat empty.

Two independent causes in `GraphEmptySpaceFinder.find`:

1. **The search is anchor-major and first-fit across anchors.** `DominantStationLabel.X_FRACTIONS`
   is `[0.08, 0.22, 0.78, 0.35, 0.65, 0.5]` and the *first* anchor yielding any legal box returns
   immediately. Anchor `0.08` had a legal box in the bottom band, so `0.78` — the wide-open
   top-right — was never evaluated. "Legal" only means curve clearance >= `padPx`, which for this
   label is 2dp.
2. **Obstacle proximity was invisible to the score.** Clearance was measured against curves only;
   already-drawn labels (`drawnBounds`) were a binary `intersects()` veto. A box 1px from the delta
   label scored exactly the same as one alone in an empty quadrant, so the mirrored `X_FRACTIONS`
   that are supposed to keep the delta and dominant labels at opposite ends did not prevent them
   landing shoulder-to-shoulder.

## What changed

`shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphEmptySpaceFinder.kt` — the search now
runs twice:

- **Pass 1 ("wide open")**: every anchor in preference order, requiring
  `min(curve gap, nearest-obstacle gap) >= openClearancePx`, where the default
  `openClearancePx = max(3 * padPx, metrics.height)` — one full line-height of air on all sides.
  Obstacle gap is the rectangle *separation distance* (`hypot` of the axis gaps), so a diagonal
  neighbour correctly reads as far away.
- **Pass 2 ("tight", unchanged)**: if pass 1 finds nothing, re-run with the pre-existing rule —
  curve gap >= `padPx`, obstacles vetoed only on intersection, scored on curve clearance alone.

Anchor preference order is preserved *within* each pass, so the mirrored `X_FRACTIONS` contract
between `ForecastDeltaLabel` (centre-first) and `DominantStationLabel` (edges-first) still holds.
`openClearancePx` is an optional parameter so callers/tests can tune or disable the first pass.

Both platforms call this one shared object (Android `TemperatureGraphAnnotationRenderer`, desktop
`TemperatureGraph.kt`), so the fix lands on the widget and the desktop app together.

## Verification

- `GraphEmptySpaceFinderTest`: new cases for (a) a tight-but-legal early anchor losing to a
  wide-open later anchor, (b) obstacle *proximity* (not just intersection) disqualifying a slot in
  pass 1, (c) the pass-2 fallback still returning the tight slot when nothing is wide open.
- Existing `GraphEmptySpaceFinderTest` / `DominantStationLabelTest` / `ForecastDeltaLabelTest` /
  `TemperatureGraphLabelPlacementRobolectricTest` must stay green.
- Emulator screenshot before/after.

## Follow-up, same day: the NOW line

The new top-right position put the label straight across the dashed NOW indicator on both the
emulator and the Samsung Fold. The finder could not see it: `curveYsAt` answers "which y is drawn at
this x" and cannot express a vertical, and the line was in no obstacle list.

- `GraphEmptySpaceFinder.find` gains **`vetoBounds`** — obstacles that block a box they *overlap* but
  never contribute distance to the pass-1 score. That distinction is load-bearing: scoring the NOW
  line's distance would push labels a full line height off it and so refuse the narrow strip of plot
  to its right, which is the only room left on an 18h window. `ForecastDeltaLabel.place` and
  `DominantStationLabel.place` both take and forward it.
- `NowIndicatorGeometry.nowLineBounds()` builds the rect from the same `computeNowLine` the renderers
  draw with, inflated by the stroke width so a label beside it reads as beside rather than touching.
  Because the line spans only `NOW_LINE_HEIGHT_FRACTION` (60%) of the plot height centred, a label in
  the top or bottom band clears it at any x — a full-height rect would wrongly evict it sideways.
- Android `TemperatureGraphAnnotationRenderer.nowLineVeto()` and desktop
  `DesktopGraphUtils.nowLineVetoBounds()` supply it, both via the shared geometry.
- `DominantStationLabel.X_FRACTIONS` gains a trailing **0.92f** right-edge anchor (mirror of the
  existing 0.08f left-hug lead). With NOW two thirds across, 0.78f centres the label ON the line and
  clearing it needs a box pushed against the right edge; the anchor clamps to the minimum 2dp inset,
  so no significant right margin is left.

## Status

Implemented 2026-08-16. Unit tests green (`:shared` graph suite, `:app` graph/label suites);
verified on the emulator and the Samsung Fold SM-F936U1 — the label sits in the open band right of
the NOW line, clear of it, hugging the right edge.
