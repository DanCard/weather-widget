# Hide Actuals for Non-Past Days in Forecast History

## Objective
Prevent the display of "Location actual" and "API actual" values in the forecast history view for today and future days, as these values are not finalized and could cause confusion.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`: Manages the display of forecast history and actuals.

## Implementation Steps
1.  **Modify `ForecastHistoryActivity.kt`**:
    -   In the `displayData` method, add a check to see if the target `date` is before `LocalDate.now()`.
    -   Update the assignments of `apiHigh`, `apiLow`, `appHigh`, and `appLow` to be `null` if the date is not a past date.
    -   Specifically, change the block around line 464 from:
        ```kotlin
        val apiHigh = actualWeather?.highTemp
        val apiLow = actualWeather?.lowTemp
        val appHigh = appActual?.highTemp
        val appLow = appActual?.lowTemp
        ```
        to:
        ```kotlin
        val isPastDate = date.isBefore(LocalDate.now())
        val apiHigh = if (isPastDate) actualWeather?.highTemp else null
        val apiLow = if (isPastDate) actualWeather?.lowTemp else null
        val appHigh = if (isPastDate) appActual?.highTemp else null
        val appLow = if (isPastDate) appActual?.lowTemp else null
        ```

## Verification & Testing
1.  Open the app and navigate to the Forecast History view for a past day. Verify that both "Location actual" and "API actual" are displayed correctly in the footer and on the graphs.
2.  Navigate to the Forecast History view for *today*. Verify that the actuals are hidden.
3.  Navigate to the Forecast History view for a *future* day. Verify that the actuals are hidden.