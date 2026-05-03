# Adjust Night Rain Label Tucking Logic

## Objective
Improve the vertical placement of the nighttime rain chance label on constrained devices (like Samsung foldables) to prevent overlap with the day-of-week labels at the bottom of the widget, while maintaining the current layout on devices with ample vertical space (like Pixel phones).

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/DailyForecastRainLabelRenderer.kt`**: Contains the logic for positioning the night rain label, specifically in the `resolveNightAnchorBaseline` function.

## Proposed Solution
The vertical placement relies on a `tightFraction` that evaluates how much "runway" exists beneath the label (`roomBelowDp`). Currently, it scales between `18dp` and `6dp`. 
We will modify the scaling thresholds and increase the maximum upward overlap when space is tight.

### Implementation Steps

1. **Modify `resolveNightAnchorBaseline` in `DailyForecastRainLabelRenderer.kt`**:
   Update the calculations for `tightFraction`, `dynamicOverlapDp`, and `dynamicNudgeDp`.

   **Current Logic:**
   ```kotlin
   val tightFraction = (1f - (roomBelowDp - 6f) / (18f - 6f)).coerceIn(0f, 1f)
   val dynamicOverlapDp = 2.0f + (1.0f * tightFraction)
   val dynamicNudgeDp = 1.5f + (1.5f * tightFraction)
   ```

   **Proposed Logic:**
   ```kotlin
   // Expand the detection range to start tucking sooner (26dp) and max out at 12dp.
   val tightFraction = (1f - (roomBelowDp - 12f) / (26f - 12f)).coerceIn(0f, 1f)
   
   // Increase the maximum upward tuck from 3.0dp to 5.0dp when constrained.
   // On a Pixel (plenty of room), tightFraction = 0, so overlap remains a standard 2.0dp.
   // On a Samsung (constrained), tightFraction > 0, scaling up to a 5.0dp overlap.
   val dynamicOverlapDp = 2.0f + (3.0f * tightFraction)
   val dynamicNudgeDp = 1.5f + (2.5f * tightFraction)
   ```

## Verification & Testing
1. **Compile & Run Unit Tests**: Execute `./gradlew test` to ensure no syntax errors.
2. **Emulator Verification (Constrained)**: Run the widget on a constrained emulator (e.g., Foldable or smaller screen) and verify via `logcat` and visual inspection that the nighttime rain chance label is tucked sufficiently to avoid the day labels.
3. **Emulator Verification (Ample Space)**: Run the widget on a Pixel-sized emulator and verify that the label does not unnecessarily tuck, keeping the `tightFraction` at or near `0.0`.
