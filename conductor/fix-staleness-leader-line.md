# Fix Staleness Indicator Leader Line

## Objective
Prevent the staleness indicator (age label under the fetch dot) from unnecessarily drawing a leader line when it slightly overlaps other labels or is bumped a short distance away due to collisions.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

## Implementation Steps
1. **Increase Overlap Threshold:**
   - In `computeFetchDotBounds`, update `val minorOverlapThreshold = ctx.paints.stalenessTextPaint.textSize * 0.40f` (changed from `0.20f`).
   - In `drawFetchDot`, update `val minorOverlapThreshold = ctx.paints.stalenessTextPaint.textSize * 0.40f` (changed from `0.20f`).
   - This allows the staleness label to overlap vertically with other text by up to 40% of its font size before being considered a collision.

2. **Delay Leader Line Drawing:**
   - In `drawFetchDot`, modify the leader line drawing condition from `if (step > 0)` to `if (step > 2)`.
   - Each collision resolution step bumps the label by `2dp`. Waiting until `step > 2` means the label can be shifted up to `4dp` away from its ideal spot without drawing a connecting line, as it will still be visually proximate to the dot.

## Verification & Testing
- Deploy to emulator and observe the fetch dot in the temperature graph.
- Verify that the staleness text sits closer to the fetch dot and no longer has a leader line unless pushed significantly far away (e.g., when labels are highly clustered).