# Fix Concurrency Bug in Daily Forecast Rain Label Font Scaling

## Objective
Use a Test-Driven Development (TDD) approach to fix a thread-safety bug in `DailyForecastGraphRenderer.kt` where multiple background threads simultaneously mutate a shared `PaintSet` object's `textSize`, causing the rain probability font size to progressively shrink.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`

## Implementation Steps
1. **Write Failing Test First**: 
   - Create a new test case in `DailyForecastGraphRendererRoboTest.kt` (e.g., `testRainLabelScalingDoesNotMutateSharedPaint`).
   - The test should trigger `renderGraph` with forecast data containing rain probabilities designed to trigger scaling (e.g. 5 days out, 39% chance).
   - Before applying any fix, assert that the shared `rainTextPaint.textSize` remains identical after rendering completes.
   - Run the test to confirm it **fails** against the current codebase (proving the state mutation).
2. **Implement the Fix**:
   - In `DailyForecastGraphRenderer.kt`, modify the `drawDailyRainLabel` function.
   - Instead of saving `originalTextSize` and mutating `paints.rainTextPaint.textSize` on the shared `PaintSet`, instantiate a local `Paint` copy (`Paint(paints.rainTextPaint)`) and apply the scaled text size to it.
   - Use the local `Paint` instance to measure and draw the rain label.
   - Remove the `try...finally` block that previously restored the `originalTextSize` (since it's no longer modified).
3. **Verify the Fix**:
   - Rerun the `testRainLabelScalingDoesNotMutateSharedPaint` test to confirm it now **passes**.

## Verification & Testing
- The TDD workflow described above serves as the primary verification.
- Run the full suite (`./scripts/emulator-tests.sh` or `./gradlew test`) to ensure no regressions.