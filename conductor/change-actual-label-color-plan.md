# Background & Motivation
The user asked why a `51.9°` label appeared in an orange/gold color on the hourly graph, and requested that the actual temperature labels be updated to match the "red thermometer" color.

- **Why is the forecast label orange/gold?** Forecast temperature labels in the hourly graph dynamically change color based on the predicted weather condition for that hour. The `51.9°` label to the right of the "NOW" line is a forecast label for a sunny period, so it is rendered in amber/gold (`WeatherConditionColors.FORECAST_SUNNY`, which is `#F4C542`).
- **Color update:** Currently, the actual temperature labels in the hourly graph use a light pink color (`#FFB3C6`). The user wants these labels to match the "red thermometer color", which corresponds to `WeatherConditionColors.OBSERVED` (`#FF3366`) and is already used for the actual temperature line.

# Scope & Impact
- Updates `TemperatureGraphRenderer.kt` to use `WeatherConditionColors.OBSERVED` for `COLOR_ACTUAL_LABEL` instead of the current light pink color.
- Ensures visual consistency between the actuals line, the fetch dot, and the actuals temperature labels.

# Proposed Solution
1. In `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`, modify the `COLOR_ACTUAL_LABEL` constant.
   - **Old:** `private val COLOR_ACTUAL_LABEL = Color.parseColor("#FFB3C6")`
   - **New:** `private val COLOR_ACTUAL_LABEL = WeatherConditionColors.OBSERVED`

# Verification
1. Review the code to ensure the color constant is correctly updated to use `WeatherConditionColors.OBSERVED`.
2. Observe the widget on the emulator to verify that the actual temperature labels in the hourly graph now match the hot pink / red color.