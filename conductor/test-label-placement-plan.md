# Test Label Placement Plan

## Objective
Fix the failing unit tests (`TemperatureGraphRendererLabelPlacementTest`) to properly verify the proximity-first label placement logic without fragile static mocking issues.

## Background
The previous tests failed because `mockk`'s `returnsMany` with a `List` may not have unpacked the booleans correctly for sequential calls, or the exact sequence of `RectF.intersects` calls was misaligned due to other labels (like `LOW`) being placed or skipped due to off-screen checks.

## Implementation Steps
1. **Update Mock Sequence**: Modify `TemperatureGraphRendererLabelPlacementTest.kt` to use explicit sequential returns:
   `every { RectF.intersects(any(), any()) } returns true andThen false andThen false andThen false`
2. **Ensure On-Screen Placement**: Make sure `heightPx` is sufficiently large (e.g., 1000) so that `LOW` and other labels are definitively on-screen and don't skip their checks, which would shift the expected number of `intersects` calls.
3. **Targeted Mocking**: Instead of mocking `RectF.intersects` globally which is fragile if other labels call it, we will use a more robust verification or adjust the `returnsMany` sequence by adding `println` debugging if needed to see exactly how many times it's called before `HIGH`.
   *Alternative*: We can set `isEssential = false` for the test data if possible, but the code hardcodes "HIGH" as essential. We will just provide a robust sequence of booleans.

## Verification
Run:
`./gradlew :app:testDebugUnitTest --tests com.weatherwidget.widget.TemperatureGraphRendererLabelPlacementTest`
Ensure all tests pass.