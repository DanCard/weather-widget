# Fix: night rain-chance label clips the degree symbol of its own day's low

## Context

On the Samsung (SM-F936U1) daily forecast view, Friday's night rain-chance label
("12%") slightly overlaps the **degree symbol** of Friday's low ("56°"), while the
whole column **below** the low label is empty.

Root cause (pixel-verified from a device screenshot):

- The night label is an **interstitial**: it is shifted right into the gap between
  its day and the next column, and its vertical anchor is
  `anchorBaseline = min(thisDay.lowLabelBaseline, rightNeighbor.lowLabelBaseline)`
  — i.e. it aligns to whichever of the two adjacent low labels sits **higher** on
  screen (`resolveNightAnchorBaseline`, `DailyForecastRainLabelRenderer.kt:222-228`).
  This is deliberate and is enshrined by the test
  `renderGraph_nightRainLabelInterstitialAnchorsBelowHigherLabel` (RoboTest:693).
- Friday's right neighbor (Saturday low **59°**) sits higher than Friday (low **56°**),
  so Friday's "12%" is anchored to Saturday's higher baseline. That height happens to
  coincide with Friday's own **degree symbol** (measured: degree at y≈1014–1031, label
  at y≈1025–1046, horizontal overlap x≈879–890).
- The placement algorithm **never treats this day's own low-temp label (degree symbol
  included) as an obstacle** — it only considers the anchor baseline and the widget
  edges. So when the neighbor-driven interstitial height lines up with this day's degree
  glyph and the rightward shift isn't quite enough horizontally, they collide.

This collision only appears when the neighbor's low is **slightly** higher than this
day's (a few °F). When the neighbor is much higher, the interstitial sits well above
this day's own low label and there is no collision (that is the existing test's case,
which must stay green).

## Desired behavior (user-chosen)

When the night label would overlap **this day's own low-temp label** rect (degree symbol
included), resolve it with this priority:

1. **Drop down if there is room** — move the label down so its top clears the bottom of
   this day's low label, provided its bottom stays within the hard floor
   (`heightPx − DAY_LABEL_BOTTOM_MARGIN`). Preferred (matches "plenty of room below").
2. **Else move right if there is room** — push the label further into the inter-column
   gap so its left edge clears the low label's right edge, provided its right edge stays
   within `widthPx − RAIN_LABEL_EDGE_MARGIN_DP` (and does not run into the right
   neighbor's low label).
3. **Otherwise leave it where it is** — never skip/drop the label; accept the slight
   overlap when neither move has room.

## Implementation

### 1. Pass this day's own low-label bounds into the night renderer
`DailyForecastGraphRenderer.drawDayColumn()`
(`app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt:703-722`)

It already computes everything needed when it draws the low label: `centerX`,
`lowTempY` (baseline), `lowLabelText`, and `tempPaint`. After drawing, build the low
label's rect once and hand it to the night renderer:

- left/right = `centerX ± measureTextWidth(tempPaint, lowLabelText) / 2`
- top/bottom = `lowTempY + tempPaint.fontMetrics.ascent` / `+ descent`

Add this as a new nullable `ownLowLabelBounds: RectF?` parameter on
`DailyForecastRainLabelRenderer.drawNightRainLabel(...)` (currently called at
`:722`). Reuse the existing `measureTextWidth` helper.

### 2. Add the collision-avoidance step in `drawNightRainLabel`
`app/src/main/java/com/weatherwidget/widget/DailyForecastRainLabelRenderer.kt:128-180`

After the current `fit` (horizontal) + `finalBaseline`/`finalTopY`/`finalBottomWithMargin`
are computed (line 149), and before the hard-bottom check (line 158):

- Build the night label rect from `fit.centerX`, `finalBaseline`, and the measured
  night-label width.
- If `ownLowLabelBounds != null` and the two rects intersect (`RectF.intersects`):
  1. **Down:** candidate baseline so the label top sits just below
     `ownLowLabelBounds.bottom` (small gap, ~1–2dp). Accept iff
     `candidateBottom <= hardBottomLimit`. Apply by replacing `finalBaseline`.
  2. **Right (only if Down failed):** candidate `centerX` so
     `leftEdge >= ownLowLabelBounds.right + edgeMargin`. Accept iff
     `rightEdge <= widthPx − edgeMargin` (and clears the right neighbor's low label if
     one is known). Apply by replacing `fit.centerX`.
  3. **Else:** leave the original placement.
- Keep the existing `finalBottomWithMargin <= hardBottomLimit` skip guard as the final
  gate, and keep emitting the `RainLabelDrawnDebug` with the final coordinates so tests
  can observe the resolved position.

Keep the existing `Log.d(TAG, "nightRainLabel position: ...")` line and add the chosen
resolution (`down`/`right`/`none`) to it for on-device debugging.

No change to `resolveNightAnchorBaseline` (the `min`/interstitial anchor stays), so the
tuck behavior and the existing interstitial test are untouched.

## Tests
`app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`

- **New** `renderGraph_nightRainLabelDropsBelowOwnLowWhenColliding`: this day low only a
  few degrees below the neighbor (e.g. Mon low=56, Tue low=59), Mon has a night label,
  widget tall enough to have room below. Assert the resolved night label rect does **not**
  intersect Mon's own low-label rect, and its baseline moved **down** (`baselineY` >
  Mon's own low baseline). This reproduces the Samsung case.
- **Regression**: confirm `renderGraph_nightRainLabelInterstitialAnchorsBelowHigherLabel`
  (Mon=30 / Tue=55, large gap → no collision) still passes unchanged
  (`baselineY < anchorBaselineY + 50f`).
- Optional small-widget case: short `heightPx` so there is no room below → assert the
  label shifts **right** (or stays put if neither has room), never disappears.

## Verification
1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.DailyForecastGraphRendererRoboTest"`
2. `./gradlew installDebug` (Samsung `RFCT71FR9NT`).
3. Re-screenshot per CLAUDE.md recipe (strip the `[Warning]` prefix before the PNG
   signature, convert to JPG) and confirm Friday's "12%" now sits in the empty space
   below "56°" with no degree-symbol overlap.
4. Eyeball other columns / a 1-day-difference day and a large-difference day to confirm
   the interstitial look is unchanged where there was no collision.
