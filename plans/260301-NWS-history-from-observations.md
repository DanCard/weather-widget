# Restore Actual History From Observations

## Objective
Dynamically reconstruct the "Actual" high/low for historical days in the daily widget view by querying the `observations` table for the min and max observed temperatures. This effectively replaces the old `weather_data` table's role for history, guaranteeing that the widget correctly draws actual historical values (especially fixing NWS dropping `lowTemp` mid-day) rather than relying solely on the latest forecast snapshot.

### How NWS Provides Historical Data
The NWS API does **not** provide a dedicated "historical high/low" endpoint. Instead, it provides a `/stations/{stationId}/observations` endpoint that returns an array of raw thermometer readings taken at regular intervals (e.g., every 15 to 60 minutes) over the past 7 days. 

Our app already queries this endpoint and stores these timestamped readings in our local `observations` database table. To determine the actual high and low for a past date, we group these raw readings by calendar day and mathematically extract the `MAX()` and `MIN()` temperature values. The `AccuracyCalculator` already does this; we just need to share its logic with the widget renderer.

### Data Retention (How long we keep observations)
The app maintains a rolling **30-day window** for observation data. In `ForecastRepository.cleanOldData()`, any records in the `observations` table older than 30 days are automatically deleted. This aligns perfectly with our navigation constraints (the widget allows browsing up to 30 days of history).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`: Will host the shared logic for aggregating raw observations into daily actuals.
- `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`: Needs its `aggregateObservationsToDaily` logic moved to `ObservationResolver.kt` so it can be reused by the widget handlers.
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` & `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`: Need to fetch `observations` and pass `dailyActuals` to the `DailyViewHandler`.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`: Will receive `dailyActuals` and pass it to `DailyViewLogic`.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Will use `dailyActuals` to set the primary `high` and `low` of past dates for the graphs and text views.

## Implementation Steps
1. **Extract Aggregation Logic**:
   - Move the `DailyActual` data class and the `aggregateObservationsToDaily` function from `AccuracyCalculator.kt` to `ObservationResolver.kt`.
   - Make `DailyActual` public so it can be referenced.
   - Refactor `AccuracyCalculator` to call the new shared method `ObservationResolver.aggregateObservationsToDaily(observations)`.

2. **Fetch Observations for the Widget**:
   - In `WeatherWidgetProvider.updateWidgetWithData`, `WidgetIntentRouter.handleDailyNavigation`, and anywhere `DailyViewHandler.updateWidget` is called, query `observationDao.getObservationsInRange` for the history window (e.g., past 30 days up to yesterday).
   - *Note*: We'll need to expose `observationDao` from `WeatherDatabase` in these classes or add a helper in the `Repository` if one doesn't exist, to keep queries clean.

3. **Update UI Handlers to Accept Actuals**:
   - Update `DailyViewHandler.updateWidget` (and its sub-methods `updateGraphMode` / `updateTextMode`) to accept `dailyActuals: Map<String, ObservationResolver.DailyActual>`.
   - Update `DailyViewLogic.prepareGraphDays` and `DailyViewLogic.prepareTextDays` to accept this map as a parameter.

4. **Wire Actuals to History Rendering**:
   - In `DailyViewLogic.kt`, when looping through dates:
     - If `isPastDate` is true, check if `dailyActuals[dateStr]` exists.
     - If it exists, use `actual.highTemp` as the base `high` and `actual.lowTemp` as the base `low` (the yellow history bar).
     - The forecast snapshot (latest prediction for that day) will still be assigned to `forecastHigh` and `forecastLow` to render the blue overlay.
     - If `dailyActuals` is missing for that date, fall back to the forecast snapshot's `highTemp`/`lowTemp` to ensure a graceful degrade.

5. **Fix Tests**:
   - Update `DailyViewHandlerTest.kt` and `DailyViewLogicTest.kt` to account for the new `dailyActuals` map parameter in method signatures.
   - Create tests to verify that past days correctly prefer `dailyActuals` values over `forecast` values when both are present.

## Verification & Testing
- Unit tests: Verify `DailyViewLogic.prepareGraphDays` assigns `actuals` to history days.
- Manual test: Build and deploy to emulator. Select NWS as source. Verify that yesterday's history correctly shows a full bar using actual observations, even if the latest NWS forecast dropped the morning low.