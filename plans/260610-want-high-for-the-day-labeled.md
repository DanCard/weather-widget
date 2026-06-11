# Always label the day's high on the hourly temperature graph

## Context

On the hourly temperature graph (the "temperature actuals" view), the **day's high is
not labeled at the top of the curve**. The emulator screenshot shows the actual (pink)
and forecast (yellow-dashed) curves both peaking right against the top edge of the graph,
with the high temperature (`87.8°`) shoved *below* the peak with an upward leader line,
and the very top of the curve carrying no label at all.

Root cause: the day-high label's preferred placement is *above* the peak. When the peak
sits near the top of the canvas, that above-placement extends past the top edge
(`bounds.top < 0`). The placement engine rejects any off-canvas position and, for the high,
ends up flipping it below the peak (or dropping it).

**Desired outcome (per user):** the day's high must always be labeled, drawn *above* the
peak even when there is no vertical room — let it spill up into the header band rather than
flip below or disappear. This is acceptable because the hourly graph bitmap spans the full
widget height behind the header (the 16dp top padding band is where the header current-temp
already overlaps the curve), so a label clamped to the bitmap's top edge lands in the
header zone as intended.

## The mechanism (for reference)

- Day high = `TemperatureRole.HIGH` at `extrema.dailyHighIndex`
  (`TemperatureLabelResolver.kt:215`, `:196`). It is the highest-priority candidate, so it
  is placed first against an empty `drawnLabelMetas` — no other labels can block it; only
  the curve.
- The drop point is in `TemperatureLabelEngine.computePlacements`
  (`shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`):

  ```kotlin
  // line 217-218
  val onScreen = bounds.top >= 0f && bounds.bottom <= heightPx
  if (!onScreen) continue   // <-- above-the-peak placement discarded here when bounds.top < 0
  ```

  Because the `continue` fires before the essential-fallback save (lines 282-288), the high
  has no above option left and flips below at the `placeAbove = false` iteration.

## Change

**File:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

Inside the `for (candidate in candidates)` loop, *before* the
`tryExactFitCurveAvoidance` pre-pass (currently line ~169), add a targeted branch for the
day-high family of roles. Only intervene when the natural above placement clips the top —
otherwise leave the existing pipeline untouched so well-fitting cases are unaffected:

- Identify the day-high candidate: `role in { HIGH, ACTUAL_HIGH, FORECAST_HIGH,
  PAST_FORECAST_HIGH }` (these are the daily-max anchors; in this view they dedup to a
  single surviving high).
- Compute the preferred *above* placement via
  `GraphLabelPlacementUtils.computeLabelVerticalPlacement(pointY = geometry.sy,
  placeAbove = true, gapPx = gapDp.aboveDp * density, ...)`.
- If `verticalPlacement.top >= 0f`, do nothing — fall through to the existing logic
  (it already places the high above correctly when there is room).
- If `verticalPlacement.top < 0f`, **clamp** the label down so its top sits at `0f`
  (shift `baselineY` and `bounds` down by `-verticalPlacement.top`), emit a `PlacedLabel`
  with `placedAbove = true`, `reason = "FORCED_TOP"`, add the clamped bounds to
  `drawnLabelMetas` (so later labels still avoid it), and `continue` to the next candidate.

This guarantees the day high is labeled at the top, overlapping the header band, instead of
being flipped below or dropped. Reuse the existing `PlacedLabel` / `PlacedLabelMeta` /
`GraphRect` constructors already used elsewhere in this function — no new types needed.

## Test updates

**File:** `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`

- `peak falls back below when above placement would leave the screen` (line 108) encodes the
  *old* behavior (`assertFalse(highPlacement.placedAbove)`). Rewrite it to assert the new
  contract: with the peak forced off the top, the `HIGH` label is **present**, is
  `placedAbove = true`, and its placement stays within the canvas top (no negative top /
  clipped text). Rename accordingly (e.g. `peak high stays labeled above when there is no
  room, clamped into the header`).
- `peak label above stays close to the forecast line` (line 132, heightPx=420) exercises the
  has-room path and should continue to pass unchanged — confirm it does.

Add one focused assertion (new test or extend an existing one) that the forced-top high's
reported bounds have `top >= 0` so the number is never clipped off the bitmap.

## Verification

1. Unit/Robolectric tests:
   ```bash
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest"
   ```
   (also run the broader label-engine tests if the change touches shared logic).
2. Build & install on the emulator:
   ```bash
   ./gradlew installDebug
   ```
3. Trigger a redraw and screenshot (per CLAUDE.md, convert PNG→JPG before reading):
   ```bash
   adb -s emulator-5554 shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE
   adb -s emulator-5554 exec-out screencap -p > /tmp/emu_hourly.png && convert /tmp/emu_hourly.png /tmp/emu_hourly.jpg
   ```
   Confirm the day's high number now sits **above** the peak at the top of the graph
   (overlapping the header band) rather than below it / missing.

## Notes / scope

- Android-only renderer path (`shared` engine drives the Android widget). The desktop temp
  graph uses a separate simpler reimplementation and is out of scope.
- Keep the intervention scoped to the *day-high* roles so ordinary local peaks are not
  forced into the header.
