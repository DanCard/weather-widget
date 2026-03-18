# Objective
Make `ObservationEntity` API-specific so that actual observations (like daily highs/lows) displayed on the widget and used for accuracy tracking are tied to the currently selected or evaluated weather API.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/data/local/ObservationEntity.kt`: The data model for observations.
- `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`: The Room database configuration.
- `app/src/main/java/com/weatherwidget/data/local/ObservationDao.kt`: Data access object for observations.
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`: Fetches current temps from APIs and saves observations.
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`: Also fetches NWS observations for history.
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`: Drives widget updates and retrieves daily actuals.
- `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`: Calculates accuracy of forecasts vs. observations.

# Implementation Steps

1. **Database Schema Update**
   - In `ObservationEntity.kt`, add `val source: String = "NWS"` to the data class.
   - In `WeatherDatabase.kt`, increment the `version` to `33`.
   - In `WeatherDatabase.kt`, add `MIGRATION_32_33` that executes: `ALTER TABLE observations ADD COLUMN source TEXT NOT NULL DEFAULT 'NWS'`. Add it to the `addMigrations` builder.

2. **DAO Query Update**
   - In `ObservationDao.kt`, update `getObservationsInRange` to accept `sourceId: String? = null`.
   - Update the `@Query` for `getObservationsInRange` to include `AND (:sourceId IS NULL OR source = :sourceId)`.

3. **Repository Updates (Saving Observations with Source)**
   - In `CurrentTempRepository.kt`, update every instantiation of `ObservationEntity` to include the `source` field.
     - `fetchSilurianCurrent`: `source = WeatherSource.SILURIAN.id`
     - `fetchOpenMeteoCurrent`: `source = WeatherSource.OPEN_METEO.id`
     - `fetchWeatherApiCurrent`: `source = WeatherSource.WEATHER_API.id`
     - `fetchNwsCurrent`: `source = WeatherSource.NWS.id`
     - In the general `fetchNwsCurrent` logic processing multiple NWS observations, set `source = WeatherSource.NWS.id`.
   - In `ForecastRepository.kt` (or wherever historical NWS observations are fetched), ensure any `ObservationEntity` created sets `source = WeatherSource.NWS.id`.
   - In `CurrentTempRepository.kt`, simplify `observationToActual(obs: ObservationEntity, source: String)` to just use `obs.source` instead of taking a separate parameter (or keep it as is, but ensure consistency).

4. **Widget Render Logic (Reading Source-Specific Actuals)**
   - In `WidgetIntentRouter.kt`, update `getDailyActuals` to accept a `source: WeatherSource? = null` parameter and pass its `id` to `observationDao.getObservationsInRange`.
   - Update all call sites of `getDailyActuals` in `WidgetIntentRouter.kt` to pass the current `displaySource`. The `displaySource` is already available in these functions (e.g., `val displaySource = widgetStateManager.getDisplaySource(appWidgetId)` or `newSource` in `handleToggleApi`).

5. **Accuracy Tracking (Evaluating against Source-Specific Actuals)**
   - In `AccuracyCalculator.kt`, when querying `observationDao.getObservationsInRange`, pass the `source` being evaluated so that Open-Meteo forecasts are graded against Open-Meteo actuals, etc.
   - Review `ForecastHistoryActivity.kt` and `WeatherObservationsActivity.kt` to ensure they either pass `displaySource` or handle the `source` column gracefully.

# Verification & Testing
- Build and deploy to the device/emulator.
- Add logging to confirm `ObservationEntity` rows are saved with the correct `source`.
- Open the widget, switch between APIs (e.g., NWS and Open-Meteo), and verify that the "actual high" for the current day updates to match the selected API's data.
- Query the database via sqlite3 or adb to verify the `source` column is populated correctly for new observations.