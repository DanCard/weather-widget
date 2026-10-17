# Night rain label: centre in the gap, nudge by collision detection

## Problem

User, 2026-10-17 (Fold 4 inner screen screenshot): the night rain-chance label between Mon and Tue
("20%") sits too far right, under Tuesday's "51°", so it reads as Tuesday's number.

Measured from the renderer's own `nightRainLabel position` logs:

| Label | Gap | Offset from gap midpoint |
|---|---|---|
| 10% | Sun/Mon | +2 dp |
| 20% | Mon/Tue | +2 dp |
| 15% | Tue/Wed | −0.3 dp |
| 55% | Wed/Thu | +3.5 dp |

## Root cause

The horizontal position is computed blind (`DailyRainLabelPlanner.calculateNightShiftedCenterX`):
gap midpoint **+1 dp** fixed, **+2.5 dp** "roomy" push right, **−1.5…3 dp** nudge left (set to 0
when the left day's low is lower). None of these offsets check what is actually there. The only
real collision test (`resolveNightCollision` / `resolveNightCollisionTop`) looks at the **left**
day's low label, and it can only move the label down. The right day's low label, which is
what "20%" runs into, is never checked.

## Plan

1. **Shared placer** (`:shared` `DailyRainLabelPlanner.placeNightLabel`), pure float math:
   - Inputs: the label size, a home position (gap midpoint, current tuck height), obstacle
     rectangles, the column width, the density, and the bottom limit (above the day-name row).
   - Obstacles: left and right low-temp labels (number + °) and left and right weather icons.
   - Candidates: home first, then left/right steps of 1 dp up to ±(columnWidth × 0.3), then
     down steps of 1 dp until the bottom limit, then the left/right steps at each lower height.
   - Cost = displacement + a heavy penalty on real overlap. **Slight overlap is allowed**: an
     intrusion of ≤ 1.5 dp, or ≤ 15 % of the label's area, costs nothing.
   - Returns the best candidate plus the reason (`home|left|right|down|down_left|down_right`)
     for the log line.
2. **Remove the blind offsets**: the +1 dp, `NIGHT_TUCK_ROOMY_RIGHT_DP` and the left nudge
   (`effectiveNudgeDp`). Keep the vertical tuck as the home height (night labels stay low, day
   labels stay up top).
3. **Android**: draw night labels in a **second pass** in `DailyForecastGraphRenderer`, after
   every column, so the right neighbour's low label and icon bounds already exist
   (`DailyColumnRenderer.DrawnBounds` already returns `lowLabel` and `icon`). Keep the existing
   bottom-overflow skip and the 0.85× shrink at the widget edges.
4. **Desktop**: same placer in `DailyForecastGraph`, with obstacles built from the boxes it
   already measures (left and right low text, icons). Delete `resolveNightCollisionTop` and
   `resolveNightCollision` once neither platform calls them.
5. **Logging**: extend the `nightRainLabel position` line with `placement=… offsetDp=(x,y)
   overlapDp=…` so the next screenshot can be checked from logcat.

## Tests (automated first)

- `:shared` `DailyRainLabelPlannerTest`:
  - empty gap → home, exactly at the midpoint;
  - right low label intruding → moves left, not down;
  - left low label + degree intruding → moves right;
  - both sides blocked → moves down;
  - 1 dp touch → stays home (slight overlap OK);
  - never goes past the bottom limit or ±30 % of the column width.
- The Fold screenshot as a fixture: the Mon/Tue geometry from the logs above must land within
  0.5 dp of the midpoint.
- Android Robolectric: night labels are drawn after every column (second pass), and the
  `onRainLabelDrawn` callbacks still fire. Font metrics are zero there, so assert geometry
  through the pure placer.
- Desktop `DailyForecastGraph` test: same fixture, same placement as Android.

## Verification

- `:shared:test`, `:desktop:test`, and the Android `DailyForecastGraphRenderer*`, architecture and
  `NightRainGridMapperTest` suites pass. 10 new placer tests in `DailyRainLabelPlannerTest`; new
  Robolectric `renderGraph_nightRainLabelIsCentredBetweenColumnsWhenNothingIsInTheWay`. The old
  `resolveNightCollision` tests are removed with the function.
- Fold 4 (2026-10-17 06:30), logcat:
  `20% gapCenterX=288.3 resolvedCenterX=280.4 placement=left offsetDp=(-3,0)` (was 293.5, i.e. +2 dp
  right); `55% placement=right offsetDp=(3,0)` (clears Wed's 51°); `10%`, `15%` `placement=down
  offsetDp=(0,1)`, centred. The screenshot shows "20%" between Mon and Tue, clear of Tue's "51°".
- Desktop popup (Google, different data): night labels centred in their gaps.
- Pixel not checked: its screen was off (dozing).
- Not done: a desktop render test with the Fold fixture. The placer is shared and tested in `:shared`;
  the desktop DrawScope has no render-test harness.

## Follow-up: the blank pocket under the ° (same day)

User (Fold 4, Silurian): the Tue/Wed "20%" looked too far right. Log:
`gapCenterX=334.4 resolvedCenterX=342.3 placement=right offsetDp=(3,0)`. It moved to dodge Tue's
"52°", but the overlap was the **blank space under the °**: each low label was one full-height box.

Fix: `DailyRainLabelPlanner.lowLabelObstacles` splits a low label ending in ° into the digits (full
height) and the ° (top `DEGREE_SIGN_HEIGHT_FRACTION` = 50 % of the box). Android `DrawnBounds.nightObstacles`,
desktop `nightLabelObstacles` both use it. Tests: split geometry, label under the pocket stays home,
label hitting the ° still moves.

Verified: Fold 4 Silurian `20% placement=home offsetDp=(0,0) obstacles=6`; screenshot shows it at the
Tue/Wed midpoint under the °. Shared, desktop and Android daily-graph tests pass; desktop restarted.
