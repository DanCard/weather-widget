# Fix Daily Graph "Today" High Temperature Label

## Objective
Ensure the numerical text label printed at the top of the "Today" column in the Daily Forecast view accurately represents the highest temperature experienced throughout the day, preventing the label from artificially dropping when the current temperature or forecasted high decreases.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (Rendering logic for the daily graph)

## Current Behavior & Root Cause
When the "Ghost Bar" feature was implemented, a new field `trueActualHigh` was added to `DayData` to track the absolute highest temperature observed so far today. The graph's visual lines correctly use this value to draw a semi-transparent "ghost bar" above the solid current-temperature bar.

However, the numerical label printed above the bars (`displayHigh`) was not updated to factor in `trueActualHigh`. Currently, it only checks `maxOf(day.high, day.forecastHigh)`. Because `day.high` is intentionally locked to the *current* temperature for today's column, if the current temperature and the forecasted high both drop below a previously reached peak, the printed text label will also drop, causing a visual discrepancy where the ghost bar extends higher than the text label.

## Proposed Solution
Update the `displayHigh` calculation in `DailyForecastGraphRenderer.kt` to explicitly include `day.trueActualHigh`.

### Implementation Steps

1. **Update `DailyForecastGraphRenderer.kt`**:
   - Locate the `"// High Temp Label"` section inside `drawDayColumn`.
   - Modify the `displayHigh` assignment.
   - Current Code:
     ```kotlin
     val displayHigh = if (day.isToday && day.forecastHigh != null) maxOf(day.high, day.forecastHigh) else day.high
     ```
   - New Code:
     ```kotlin
     val displayHigh = if (day.isToday) {
         val baseHigh = maxOf(day.high ?: 0f, day.forecastHigh ?: 0f)
         if (day.trueActualHigh != null) maxOf(baseHigh, day.trueActualHigh) else baseHigh
     } else {
         day.high
     }
     ```
   - *Self-Correction for Nullability*: Since `day.high` is nullable, we should ensure the fallback logic remains clean. A cleaner version would be:
     ```kotlin
     val displayHigh = if (day.isToday) {
         listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: day.high
     } else {
         day.high
     }
     ```

## Verification & Testing
1. **Unit Tests**: Run `DailyForecastGraphRendererTest` (and related UI rendering tests) to ensure no regressions.
2. **Visual Verification**: 
   - Deploy to the emulator.
   - Use the system's test data or manually mock a scenario where the `trueActualHigh` (e.g., 85) is greater than both the current `high` (e.g., 70) and the `forecastHigh` (e.g., 80).
   - Verify that the label printed at the top of the Today column reads "85.0°" and sits neatly at the peak of the ghost bar.