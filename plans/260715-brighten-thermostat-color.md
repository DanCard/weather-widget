# Plan: Brighten Thermostat Color

## Goal
Experiment with a brighter color for the thermostat color (current observations and actuals line/bars) and verify/deploy the changes.

## Proposed Changes
1. **Brighten color constants**:
   - Change `WeatherColors.OBSERVED` in `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherColors.kt` from `0xFFFF7799` (light salmon) to `0xFFFF88AA` (bright rose pink).
   - Change `WeatherConditionColors.OBSERVED` in `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt` from `#FF7799` to `#FF88AA`.
2. **Unify Desktop Hourly Actuals Color**:
   - Update `COLOR_ACTUAL` in `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` to reference `com.weatherwidget.shared.util.WeatherColors.OBSERVED` directly, enforcing single source of truth and parity.
3. **Verify and Deploy**:
   - Run tests to check for failures or regressions:
     ```bash
     ./gradlew test
     ```
   - Start the emulator (`Medium_Phone_API_36`).
   - Build and deploy to the emulator:
     ```bash
     ./gradlew installDebug
     ```
   - Take a screenshot of the widget on the emulator to visually confirm the new brighter color.
