# Change hourly graph default view to exclusive 24-hour range

## Summary of Changes

### Core Implementation
* **Exclusive Point Accumulation**: Changed the loop condition from inclusive (`currentHour.isBefore(endHour) || currentHour.isEqual(endHour)`) to exclusive (`currentHour.isBefore(endHour)`) across all weather view handlers:
  * [PrecipViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt)
  * [TemperatureStateResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt)
  * [CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt)
  * [ActualTemperatureSeriesBuilder.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt)
* **Desktop App Boundaries**: Updated filters and fallbacks to exclude `cutoff` (the end hour boundary):
  * [HourlyGraphInput.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/HourlyGraphInput.kt)
  * [TemperatureGraph.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt)

### Test Adjustments
* Updated test expected sizes to reflect exclusive loops (e.g. `WIDE` zoom has `24` points; `NARROW` has `4` points):
  * [PrecipGraphQueryWindowTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/PrecipGraphQueryWindowTest.kt)
  * [CloudCoverViewHandlerTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt)
  * [TemperatureViewHandlerActualsTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerActualsTest.kt)
  * [HourlyZoomCenteringRoboTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/HourlyZoomCenteringRoboTest.kt)
  * [TemperatureGraphWindowTest.kt](file:///home/dcar/projects/weather-widget/desktop/src/test/kotlin/com/weatherwidget/desktop/TemperatureGraphWindowTest.kt)

## Verification Results

* **Local Unit Tests**: Successfully ran `./gradlew test` (all 1,395 unit tests passed).
* **Instrumented Tests**: Successfully ran `./scripts/emulator-tests.sh` on emulator (all 61 tests passed).
