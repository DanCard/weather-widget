# Remove the weather widget's edge padding

User request (2026-11-18): "Get rid of edge padding for weather widget", after comparing the Fold 4
right edge with the Daily Calendar widget below it.

## Evidence (Fold 4, SM-F936U1, 420 dpi = 2.625 px/dp)

From a `uiautomator` view dump and a pixel scan of the screenshot:

| | Weather widget | Daily Calendar |
|---|---|---|
| Host view (`AppWidgetHostView`) right edge | 1630 | 1630 |
| Drawing area right edge | `graph_view` 1603 (27 px in) | `daily_canvas` 1630 (fills host) |
| Rightmost drawn pixel | 1591 (gear), 1585 ("61°") | 1622 ("Thu 19") |

- Root cause: `widget_weather.xml` gives `widget_root` 8 dp of padding on every side, plus 4 dp
  left/right margins on `graph_view` and every overlay zone container that tracks it. The
  calendar's `widget_daily.xml` has its canvas edge to edge.
- Side finding: the code assumes `graph_view` is 24 dp narrower than the host, which predicts
  1387 px on the Fold (1450 px host). It measured 1396 px. The bitmap is sized from the 24 dp
  assumption and `fitCenter` scales it to fit, so the mismatch is hidden. Not chased further:
  removing the inset removes the assumption.

## Proposed change (Android only)

1. **Layout** (`widget_weather.xml`):
   - `widget_root` `android:padding` 8 dp → 0.
   - 4 dp → 0 `layout_marginStart`/`End` on `graph_view`, `graph_day_zones`,
     `graph_interaction_container`, `graph_bottom_zone`, `graph_bottom_day_zones`,
     `graph_night_rain_zones`, `graph_bottom_hour_zones`, and `text_container`'s `marginEnd`.
2. **One source for the inset.** The 8 dp / 4 dp numbers are copied in Kotlin today. Replace them
   with one `WidgetInsets` object in `:app` (`ROOT_PADDING_DP = 0`, `GRAPH_SIDE_MARGIN_DP = 0`),
   used by:
   - `WidgetSizeCalculator.computeBitmapDimensions` (default paddings 24 / 16);
   - `SimLoopTouchTarget.ROOT_PADDING_DP` and `graphViewSizeDp` / `bitmapToContentDp`;
   - `ResizeDiagnosticsLogger.GRAPH_HORIZONTAL/VERTICAL_PADDING_DP`;
   - `MonthsViewActivity.CARD_PADDING_X/Y_DP` (the in-app preview card that mimics the widget).
   - `:shared` `NavArrowGeometry.GRAPH_INSET_DP` 4 → 0. Desktop supplies its own arrow overlap
     width, so this only changes Android. The arrow then overlaps the plot by its full 40 dp.
3. **Docs**: update the `arch/` widget-layout text that mentions the inset (to locate with `rg`).

What moves to the very edge: the current temperature (left), the API label and settings gear
(right; `top_right_header_container` already has `marginEnd=-10dp` and may need resetting to 0),
the nav arrows, and the day names at the bottom. The translucent `#80000000` background with
16 dp rounded corners stays, so the corners of the content sit over the rounded corners.

## Decision needed

- **Which edges?** The calendar has no padding on any side. Proposal (Claude's): all four sides,
  to match it. The alternative is left/right only, keeping 8 dp top and bottom.
- **Desktop.** It's a window, not a home-screen widget next to the calendar. Proposal (Claude's):
  leave it unchanged.

## Tests

- **Integration** (Robolectric, new): inflate `widget_weather.xml` in a 400 × 220 dp root. Assert
  that `graph_view` and every zone container fill the root, and that their size matches
  `computeBitmapDimensions` and `SimLoopTouchTarget.graphViewSizeDp`. It ties the XML to the
  constants and fails on the current layout (8 + 4 dp inset).
- Update the fixed expectations in `SimLoopTouchTargetTest` (376 × 204 → 400 × 220) and any
  `NavArrowGeometry` overlap tests.
- Device: Fold 4 + emulator screenshots and `uiautomator` bounds.
  - `graph_view` == host bounds, and the rightmost ink is close to the calendar's.
  - Taps still land: a day column, nav arrows, settings gear, the API label, the sim-loop pill.
- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Addendum: nav arrows on the edge

User, after the first install: "Left and right arrows need to move to the edge."

- The chevron's ink sat in the middle 8 of its 24 dp viewport, inside 10 dp button padding, so it
  ended ~17 dp from the widget edge.
- `ic_chevron_left_edge` / `ic_chevron_right_edge` (widget-only) shift the path to the viewport's
  outer edge. `nav_left` / `nav_right` keep the 40 dp hit area, with padding 0 on the edge side and
  16 dp inside. `DailyViewHandler.setupNavigationButtons` re-applies the same padding at runtime,
  where it used to reset `nav_left` to 10 / 10.
- Test: `WidgetEdgeInsetRoboTest` "nav arrow chevrons sit on the widget edge" (fails on the old
  layout).
- Fold 4: the right chevron ink spans 1614–1628 px (widget edge 1630); the left spans 181–195
  (edge 180).

## Finding: Pixel Launcher clips the corners

On both Pixel Launcher emulators, the launcher's rounded-corner clip cuts the header corners: the
current temperature's first digit and the gear. Samsung One UI (Fold 4) does not clip. Open: how to
handle it (user to decide).

## Addendum: last night label to the new edge

User: "fold 4: Far right: 60% night time label: can we move that to the new far right edge?"

- Evidence (Fold 4 logcat): the bitmap is 566 px at `bitmapScale=0.326`, and `fit=NIGHT_AT_EDGE target=545.5`.
  566 − 10.5 − 10 = 545.5: the 4 dp edge margin used the device density (2.625) in bitmap px,
  which is ~10 dp on screen.
- Fix: `DailyRainLabelLayout.NightInput.bitmapScale` (default 1, desktop). Android passes
  `layout.bitmapScale`. The margin is `RAIN_LABEL_EDGE_MARGIN_DP × pxPerDp × bitmapScale`.
- Result: `target=552.6 edgeMargin=3.4`; "60%" ink now ends ~10 screen px (4 dp) from the widget edge.
- Tests (both fail on the old formula):
  - `DailyRainLabelLayoutTest` "on a downscaled bitmap …";
  - `DailyForecastGraphRendererRoboTest.renderGraph_lastColumnNightRainLabelEdgeMarginIsScreenDpOnADownscaledBitmap`.
- Noted, not changed: the night placer's other dp values (clearance, row gap, search step) use the same
  device-density conversion, so on downscaled bitmaps they are ~3× larger on screen than their names say.
  The current weights were tuned on screen against those effective sizes.
