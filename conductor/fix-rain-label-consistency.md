# Rain Label Positioning Consistency — Implementation Plan

This plan addresses the visual inconsistency between different night rain labels (specifically 12% vs 24%) on the emulator.

## Objective
- Ensure all night rain labels (except the last column) are consistently shifted into the inter-column gap.
- Prevent larger labels (like 24%) from feeling "too tucked in" by using top-relative vertical positioning.
- Improve the visual "tuck" of smaller labels (like 12%) so they feel centered in the vertical gap.

## Proposed Changes

### 1. DailyForecastGraphRenderer.kt

#### Adjust Vertical Positioning Logic
- Instead of anchoring the **baseline** at a fixed offset from the temperature, anchor the **top** of the text.
- This ensures that larger fonts (24%) and smaller fonts (12%) have the same "breathing room" above them.
- New constant: `NIGHT_RAIN_TOP_GAP_DP = 1.5f` (distance from temp descent to rain top).

#### Refine Horizontal "Shift" Logic
- Relax the `canShiftStandard` check. If the label is in any column before the last one, it SHOULD be shifted to the right boundary of its column.
- If it's too close to the right edge of the widget, allow it to "bleed" slightly or scale down further, rather than snapping back to the center of the column. This maintains the "gap" look.

#### Adjust Constants
- `NIGHT_INTERSTITIAL_H_NUDGE_DP`: Keep at `1.5f` (leftward nudge).
- `NIGHT_RAIN_TEMP_OVERLAP_DP`: Deprecate in favor of top-based gap.

## Implementation Steps

1.  **Switch to Top-Based Anchoring**: Update `drawNightRainLabel` to calculate the baseline based on `topY - metrics.ascent`, where `topY` is a fixed distance from the temperature label's bottom.
2.  **Improve Edge Shifting**: Update the `placementType` logic to prioritize the `NIGHT_SHIFTED_LEFT` position even for the last-but-one column, provided it doesn't literally exit the bitmap.
3.  **Verification**:
    - Force refresh and check the 3-day view (where 12% was centered) and the 9-day view (where 24% was crammed).
    - Analyze logs to confirm consistent `placement=NIGHT_SHIFTED_LEFT`.

## Verification & Testing
- **Visual Audit**: Confirm 12% is now in the gap and 24% has more air above it.
- **Log Audit**: Check that both labels use the same `placement` strategy.
