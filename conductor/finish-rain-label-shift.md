# Plan: Finalize Rain Chance Implementation and Fix Tests

This plan finishes the implementation of shifting night rain labels to inter-column gaps and fixes the unit tests that were invalidated by these changes.

## Objective
- Update unit tests to expect both day and night rain labels when both are significant.
- Adjust the renderer test for "skipped when too wide" to account for the new multi-step scaling strategy.
- Maintain the `+ 10` precipitation threshold baseline as confirmed by the user.

## Known Limitations
- **Emulator Visibility**: This plan focuses on test stability and fixing logic regressions. It is acknowledged that night rain labels may still not appear on the emulator in certain conditions (e.g., due to remaining threshold or layout constraints), which will be addressed in a follow-up phase.

## Implementation Steps

### 1. Update DailyViewLogicTest.kt
Update the test case that was asserting a null night label when a day label is present.
- **File**: `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- **Change**: In `prepareGraphDays future NWS rain label uses direct daytime chance instead of legacy daily chance`, change `assertNull(futureDay.rainData.nightRainLabelText)` to `assertEquals("80%", futureDay.rainData.nightRainLabelText)`.

### 2. Update DailyForecastGraphRendererRoboTest.kt
Update the "too wide" test to ensure it still tests the skipping behavior correctly given the new scaling logic.
- **File**: `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`
- **Change**: In `renderGraph_nightRainLabelIsSkippedWhenTooWide`, increase the label length to something that will definitely exceed the 40px width even after 85% scaling (e.g., "100000000000000000000%").

## Verification & Testing
- Run `./gradlew test` to ensure all 1076+ tests pass.
- Verify visually (if possible) that the night labels now sit between columns.
