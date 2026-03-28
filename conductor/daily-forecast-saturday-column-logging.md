# Plan: Add Logging for Daily Forecast Column Visibility

## Background & Motivation
In the emulator (particularly wide ones like foldable devices), the Daily Forecast view is not showing the "Next week Saturday" column. The widget dynamically scales the number of columns based on width, but the daily forecast graph loop silently skips days if there is no forecast data available for them. 

The most likely cause is that the NWS API only provides 7 days of forecast data (Today through next Friday). When the widget tries to render the 8th or 9th day (Next Saturday), `weatherByDate[date]` is null, causing the day to be skipped and effectively shrinking the number of columns rendered.

To confirm this, we need to add explicit logging to identify exactly which days are being skipped and why.

## Proposed Solution
Add debug logging inside `DailyViewLogic.kt` where days are excluded from the `prepareGraphDays` list.

### Implementation Steps
1. **Modify `DailyViewLogic.kt` (`prepareGraphDays`)**:
   Add logging before the `return@forEachIndexed` statements that filter out days with missing data.
   
   ```kotlin
            if (weather == null && actual == null && forecast == null) {
                Log.d(TAG, "prepareGraphDays: skipping $date because weather, actual, and forecast are all null (cols=$numColumns, src=${displaySource.id})")
                return@forEachIndexed
            }
            
            if (!isToday && !isPastDate) {
                if (weather?.highTemp == null || weather.lowTemp == null) {
                    Log.d(TAG, "prepareGraphDays: skipping future $date due to missing high/low temps")
                    return@forEachIndexed
                }
            } else {
                if (weather?.highTemp == null && weather?.lowTemp == null && actual == null && forecast == null) {
                    Log.d(TAG, "prepareGraphDays: skipping past/today $date due to missing temps/actuals")
                    return@forEachIndexed
                }
            }
   ```

2. **Note on Text Mode**:
   Currently, `prepareTextDays` is hardcoded to 7 items: `val daySlots = listOf(-1, 0, 1, 2, 3, 4, 5).mapIndexed ...`. If the issue also affects text mode, we should replace this with a dynamic calculation using `NavigationUtils.getDayOffsets`. (We'll leave this out of scope unless the logging shows it's necessary).

## Verification & Testing
1. Apply the logging.
2. Run the widget in the foldable emulator (`Generic_Foldable_API36`) with NWS.
3. Check logcat for `DailyViewLogic` to see if "Next week Saturday" (offset 7) is explicitly skipped due to "weather, actual, and forecast are all null".
