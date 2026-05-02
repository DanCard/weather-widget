# Rain Label Positioning Optimization — Implementation Plan

This plan optimizes the positioning of night-time rain chance labels in the daily forecast view to address feedback that they appear too low and shifted too far to the right.

## Objective
- Improve visual balance by nudging night rain labels towards the preceding day's column, taking advantage of the empty space under the degree symbol.
- Bring the labels slightly higher to feel more "tucked" under the temperature values.

## Proposed Changes

### 1. DailyForecastGraphRenderer.kt

#### Adjust Constants
- Increase `NIGHT_RAIN_TEMP_OVERLAP_DP` from `3f` to `4f` to pull the labels higher.
- Set `NIGHT_INTERSTITIAL_V_DROP_DP` to `0f` to remove the extra vertical drop.
- Potentially increase `NIGHT_INTERSTITIAL_H_NUDGE_DP` from `2f` to `3f` for a more distinct shift towards the degree symbol.

#### Update `drawNightRainLabel` Logic
- Modify the horizontal nudge logic to always shift left (negative X) by `hNudge`.
- This overrides the current logic which nudges *away* from the warmer (lower) day. By always nudging left, we consistently use the "degree symbol buffer" of the preceding day.
- Ensure the nudge also applies to the "Fallback" (centered) case if it's the boundary between two days with identical low temperatures.

## Implementation Steps

1.  **Modify Constants**: Update the `DP` constants in `DailyForecastGraphRenderer`.
2.  **Refactor Nudge Logic**: Simplify the horizontal coordinate calculation in `drawNightRainLabel` to always apply the leftward nudge when in the interstitial or shifted state.
3.  **Verification**:
    - Use `adb shell screencap` to verify the new positioning on the emulator.
    - Check for any collisions with digits when the left day is significantly warmer than the right.

## Verification & Testing
- **Visual Audit**: Confirm that "12%" and "24%" labels look centered in the visual gap rather than the mathematical boundary.
- **Regression**: Ensure daytime rain labels (above high temps) are unaffected.
- **Extreme Case**: Verify a 0° vs 90° transition doesn't cause text overlap.
