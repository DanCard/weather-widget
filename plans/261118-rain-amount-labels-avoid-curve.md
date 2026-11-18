# Rain-amount labels avoid the rain-chance curve

## Evidence

Fold 4 (RFCT70G352Y), 2026-11-18 12:18, WAPI, precip view: `Act .4in` at ~9a drawn on the curve
(curve ~85-91% there). Surfaced after
[261118-actual-rain-label-anchor-elapsed-hours](261118-actual-rain-label-anchor-elapsed-hours.md)
moved the day Act anchor from the run midpoint to the elapsed midpoint.

## Root cause

`RainAmountLabelPlacer.calculatePlacements` (`GraphEmptySpaceFinder.kt`) fixes x at `anchorX` and
walks `OVERLAY_Y_FRACTIONS` top-down, accepting the first candidate free of *label* collisions. It
has no knowledge of the curve, so a candidate the line passes through is accepted. Applies to
Pred and Act, both platforms.

## Fix (`:shared`)

- New required `curvePoints: List<Pair<Float, Float>>` (curve vertices, x ascending).
- A candidate whose padded box the curve passes through (sampled across its width with
  `SimLoopPill.curveYAt`; a y inside the box, or above/below flipping between samples) is not
  clear. The search continues to the next y.
- Fallback when nothing is clear: least label overlap first, then a candidate clear of the curve.
  (First cut priced a crossing as a label's area; on the Fold that put `Act .099in` on top of
  `Pred .25in` beside a steep 3a-7a climb. Text-over-text is worse than a line through text.)
- Android passes `layout.points`; desktop passes `coords`.

## Tests

- `RainAmountLabelPlacerCurveTest` (shared): plateau curve through the top row → label moves below
  it; fails on old code.
- `PrecipitationGraphRendererTest`: through `calculateLayout`, no Pred/Act box is crossed by the
  curve for a Fold-like high-plateau day.

## Verification

Gate + install; Fold 4 and desktop screenshots.
