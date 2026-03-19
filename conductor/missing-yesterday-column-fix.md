# Plan - Fix Missing Yesterday Column on Emulator

The "yesterday" column (Tuesday, from the perspective of Wednesday March 18) is reported as missing on the emulator. This occurs because the emulator often lacks historical weather data (actuals and snapshots) for the previous day, leading the daily graph rendering logic to skip that day entirely and incorrectly scale the remaining days.

## Root Cause
1. **Skipping Days in `DailyViewLogic.prepareGraphDays`**: If a day has no `ForecastEntity` (from preferred/gap source) and no `DailyActual` (observation), it is skipped using `return@forEachIndexed`. This means it is excluded from the `days` list.
2. **Incorrect Column Scaling in `DailyViewHandler.updateWidget`**: The handler calls `DailyForecastGraphRenderer.renderGraph` passing `days.size` as the `numColumns` parameter. 
   - If `numColumns` (the logical widget width) is 5, but yesterday is skipped, `days.size` becomes 4.
   - `renderGraph` then calculates `dayWidth = width / 4`.
   - However, the `columnIndex` for the remaining days is still based on the original 5-column grid (e.g., Today is at `columnIndex = 1`, Tomorrow at `2`, etc.).
   - This results in slot 0 (yesterday) being an empty gap, but the whole graph being drawn at 4-column scale. The last day (`columnIndex = 4`) is drawn at `x = width`, which is off-screen.

## Proposed Changes

### 1. DailyViewLogic.kt
- In `prepareGraphDays`, remove the conditional `return@forEachIndexed` statements that skip days with no data.
- This ensures the `days` list always contains exactly as many elements as `dayOffsets` (usually matching `numColumns`).
- `DailyForecastGraphRenderer.DayData` already handles nulls for temperatures and icons, and `renderGraph` skips drawing the bar (but draws the label) for days with no data.

### 2. DailyViewHandler.kt
- In `updateWidget`, update the call to `DailyForecastGraphRenderer.renderGraph` to pass the correct `numColumns` variable instead of `days.size`. This provides a second layer of protection against layout shifting if the list size ever deviates from the logical grid size.

## Verification & Testing
- **Manual Verification (Emulator)**: Verify that the yesterday column (e.g., "Tue") now appears in the daily graph, even if it has no temperature bar. Verify that Today is in the correct slot and the last day is visible on the right.
- **Unit Tests**:
  - Add a test case to `DailyViewLogicTest.kt` (if it exists) to ensure `prepareGraphDays` returns the correct number of days even when some dates have no data.
  - Verify that `DailyForecastGraphRenderer` handles `DayData` with null `high`/`low` correctly (renders label, skips bar).

## Technical Details
### File: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- Remove lines that perform `return@forEachIndexed` based on missing `weather` and `actual` data in `prepareGraphDays`.

### File: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- Line 315: Change `days.size` to `numColumns`.
