# Fix Label Placement Under Curve Plan

## Objective
Ensure that low temperature labels (like 50.4) are placed below the curve by reducing the preferred padding and allowing more minor overlap with icons/edges.

## Changes

1. **Reduce Preferred Below Padding:**
   In `GraphLabelPlacementUtils.kt`, change `PREFERRED_BELOW_GAP_DP` from `2f` to `1f`. This makes the label sit closer to the data point, reducing its overall height and making it more likely to fit on-screen.

2. **Allow More Minor Overlap:**
   In `TemperatureGraphRenderer.kt`:
   - Change `MINOR_OVERLAP_HEIGHT_RATIO` from `0.35f` to `0.45f`. This allows labels to slightly overlap icons or the widget edge if it means they can stay in their preferred `below` position.
   - Update `isMinorOverlapEligible` to include the new `ACTUAL_LOW` and `ACTUAL_HIGH` roles so they can benefit from this overlap logic.

3. **Increase Bottom Graph Buffer:**
   In `TemperatureGraphRenderer.kt`, change `MIN_BOTTOM_TEMP_BUFFER_DEGREES` from `1.5f` to `2.5f`. This ensures the graph leaves enough vertical space (in degrees) at the bottom to accommodate a label below the lowest data point.

## Verification
- Unit tests: Update tests that check `isMinorOverlapEligible` and ensure all placement tests pass with the new padding/buffer.
- Visual check: Verify on the emulator that 50.4 is now placed below the curve.
