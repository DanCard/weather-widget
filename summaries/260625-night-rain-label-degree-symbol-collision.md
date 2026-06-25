# Fix: night rain-chance label clips its own day's degree symbol

## Issue

On the Samsung (SM-F936U1) daily forecast view, Friday's night rain-chance label ("12%")
slightly overlapped the **degree symbol** of Friday's low ("56°"), while the whole column
below the low label was empty.

## Root cause

The night label is an *interstitial*: it is shifted right into the gap between its day and the
next column, and its vertical anchor is
`anchorBaseline = min(thisDay.lowLabelBaseline, rightNeighbor.lowLabelBaseline)` — i.e. it
aligns to whichever of the two adjacent low labels sits *higher* on screen
(`DailyForecastRainLabelRenderer.resolveNightAnchorBaseline`).

Friday's right neighbor (Saturday low **59°**) sits higher than Friday (low **56°**), so the
`min()` anchor pulls Friday's "12%" up to Saturday's higher baseline. Pixel-measured from the
device screenshot: Friday's degree symbol at y≈1014–1031, the "12%" landing at y≈1025–1046 with
horizontal overlap x≈879–890 — hence the slight clip. The placement algorithm never treated this
day's *own* low label (degree symbol included) as an obstacle, so the collision only surfaces when
the neighbor-driven interstitial height happens to line up with this day's degree glyph.

The collision only appears when the neighbor's low is **slightly** higher (a few °F). When the
neighbor is much higher, the interstitial sits well above this day's own low label — no collision
(and that no-collision case has its own test that had to stay green).

## Fix

`DailyForecastRainLabelRenderer.resolveNightCollision()` — a new **pure float function**: when the
night label rect intersects this day's own low-label box, it nudges the label **straight DOWN to
share the low label's baseline**, so it sits beside the number ("56° 12%") and clears the degree
symbol above. Down-only, never sideways, never past the shared baseline — so the label stays *by
the side* of the temperature rather than dropping into the empty space below.

- `drawDayColumn` (`DailyForecastGraphRenderer.kt`) builds the low label it just drew into a
  `LowLabelBox(left, top, right, bottom, baseline)` and passes it to `drawNightRainLabel`.
- `resolveNightAnchorBaseline` (the interstitial `min()` anchor) is intentionally untouched, so the
  non-colliding case is unchanged.

### Design note

The first attempt dropped the label *below the whole low label* into the empty room. The user
rejected that as "way too low" — the preference is a small downward nudge that keeps "12%" beside
the number, not a big drop and not a horizontal shift. Baseline-align is that small nudge.

## Verification

- 3 new pure unit tests in `DailyForecastGraphRendererTest.kt` (overlap → down/baseline-align;
  no horizontal overlap → leave; already-below → never move up), all green.
- Existing `renderGraph_nightRainLabelInterstitialAnchorsBelowHigherLabel` RoboTest still passes
  (no regression to the no-collision interstitial case).
- Built + installed on the Samsung; user confirmed the result visually.

## Why a pure function (not a renderer test)

In this project's renderer test environment, `Paint` font metrics come back as **0**, so any rect
built from ascent/descent is zero-height and never "intersects" — the geometry cannot be exercised
through `renderGraph`. Extracting the decision as plain float math is the only way to actually
assert the placement, which is why the codebase keeps leaning on pure-function seams
(cf. `resolveRainAboveHighPlacement`, `PrecipRect`).
