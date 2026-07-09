# Plan: Support Celsius Temperature Unit

This plan outlines the design, implementation steps, and verification strategy for adding Celsius support across both the Android home-screen widget and the Linux desktop companion app.

---

## 1. Core Architectural Decision: Canonical Storage

To prevent database migration issues and maintain full backward compatibility, **all temperatures will continue to be stored canonically in Fahrenheit (°F) in the database** (e.g., in `DailyHistoryEntity`, `HourlyForecastEntity`, `ObservationEntity`).
- Celsius/Fahrenheit conversion will be performed **dynamically at formatting and display time**.
- Internal calculations that map temperature to screen coordinates (e.g., `tempToY` ratios, grid scale computations, temperature-to-color mapping thresholds) will continue to operate on raw Fahrenheit values. Since Celsius is a linear translation of Fahrenheit, the visual graph curves and segment colors remain mathematically identical.
- Display labels, axis ticks, tray icons, IPC panel text, and diagnostics tables will check the user's unit preference and format the Fahrenheit values accordingly.

---

## 2. Shared Utilities & Calculations

We will update the shared module `:shared` to support the conversion and formatting logic.

### 2.1 Update `shared/.../util/TempUtils.kt`
Add conversion helpers:
```kotlin
fun fahrenheitToCelsius(f: Float): Float = (f - 32f) / 1.8f
fun celsiusToFahrenheit(c: Float): Float = c * 1.8f + 32f
```
Update `formatTemp` to support Celsius formatting:
```kotlin
fun formatTemp(v: Float?, useCelsius: Boolean = false): String? {
    if (v == null) return null
    val displayVal = if (useCelsius) fahrenheitToCelsius(v) else v
    val rounded = displayVal.roundToInt()
    return if (abs(displayVal - rounded) < 0.01f) {
        "$rounded°"
    } else {
        String.format(Locale.getDefault(), "%.1f°", displayVal)
    }
}
```

### 2.2 Update `shared/.../graph/TemperatureLabelResolver.kt`
- Update `formatTemp(value: Float, useCelsius: Boolean = false): String` to convert to Celsius before rounding and string formatting.
- Propagate `useCelsius` to the internal candidate generation and duplicate-filtering methods (`collectLabelCandidates`, `addForecastMidpointLabel`, `addCoincidentActuals`) to ensure semantic duplicate checking matches the displayed unit string on screen.

### 2.3 Update `shared/.../graph/ForecastEvolutionGeometry.kt`
Update the formatting helpers to accept `useCelsius` and convert accordingly:
- `formatAxisLabel(value: Float, useCelsius: Boolean = false)`: Converts absolute value.
- `formatErrorLabel(value: Float, useCelsius: Boolean = false)`: Converts temperature delta by dividing by `1.8f`.
- `formatTempLabel(value: Float, useCelsius: Boolean = false)`: Passes to `TempUtils.formatTemp(value, useCelsius)`.

---

## 3. Settings Persistence

### 3.1 Android Settings
In [WidgetStateManager](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt), add a global preference for Celsius:
- `fun useCelsius(): Boolean`: Reads `"use_celsius"` from SharedPreferences (default `false`).
- `fun setUseCelsius(value: Boolean)`: Writes `"use_celsius"`.

### 3.2 Desktop Settings
In `DesktopConfig.kt`, add a new field to `DesktopConfig`:
```kotlin
val useCelsius: Boolean = false
```

---

## 4. UI Settings Screens

### 4.1 Android Settings UI
- **Layout**: In [activity_settings.xml](file:///home/dcar/projects/weather-widget/app/src/main/res/layout/activity_settings.xml), add a card containing a Switch widget (`SwitchCompat`) with `android:id="@+id/use_celsius_switch"`.
- **Strings**: Add `units_title` ("Units") and `use_celsius_label` ("Use Celsius") to `strings.xml`.
- **Activity**: In [SettingsActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt), read the initial value from `widgetStateManager.useCelsius()` to initialize the switch. Set an `onCheckedChangeListener` that saves the state and calls `WeatherWidgetProvider.triggerUiOnlyUpdate(this, "unit_preference_changed")` to immediately redraw all home screen widgets.

### 4.2 Desktop Settings UI
- **Activity**: In `SettingsWindow.kt`, add a "Temperature Unit" section with a `Switch` that binds to `currentConfig.useCelsius`.
- **Recomposition**: Saving settings triggers `saveConfigAndNotify(newConfig)` in `Main.kt`, which updates the state of `config`, causing the entire Compose hierarchy to recompose.

---

## 5. Android Widget Rendering

We will pass the `useCelsius` flag down to all widget rendering flows:

### 5.1 Daily View Graph & Text Mode
- In [DailyGraphRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyGraphRenderer.kt), fetch `useCelsius = ctx.stateManager.useCelsius()` and pass it to `DailyForecastGraphRenderer.renderGraph`.
- In [DailyForecastGraphRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt), update `formatTempLabel(value, forceInteger)` to convert `value` to Celsius when `useCelsius` is enabled.
- Update the daily text mode layout binder in [DailyViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt) to format text labels using the Celsius preference.

### 5.2 Hourly View Graph & Text Mode
- In [TemperatureStateResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt), fetch `useCelsius` and pass it to `TemperatureGraphRenderer.renderGraph`.
- In [TemperatureGraphRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt), use `useCelsius` in label resolution and formatting calls.
- In [TemperatureTextMode.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTextMode.kt) (used on 1-row widgets), use `useCelsius` to format the current temperature, high, low, and delta fields.

---

## 6. Desktop UI Rendering

We will pass `config.useCelsius` to all desktop view components:

### 6.1 Popup UI
- In `Main.kt`, update `WidgetHeader`, `DailyForecastGraph`, `DailyForecastTextMode`, and `TemperatureGraph` calls to pass the `useCelsius` value.
- Update `formatTrayTemperature` in `TemperatureTrayPainter.kt` to check `useCelsius` and convert temperatures. Keep using raw Fahrenheit values inside `trayTempToColor` so temperature color thresholds remain accurate.

### 6.2 IPC Server (genmon plugin)
- In `PanelIpcServer.kt`, read `config.useCelsius`. Convert the current temperature and delta values (delta divided by `1.8`) before printing them to the Pango markup string.

---

## 7. Accuracy & Diagnostics UI

Both platforms feature statistics pages that display absolute temperature comparisons and average forecast errors (bias).

- **Absolute Temperatures** (Forecast High/Low, Actual High/Low): Convert using `fahrenheitToCelsius(F)`.
- **Deltas / Errors / Biases** (Average High/Low Error, Bias, Max Error): Convert by dividing the Fahrenheit error by `1.8` (since 1.8°F difference = 1.0°C difference).

We will apply these rules in the following screens:
1. **Android**:
   - [StatisticsActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/StatisticsActivity.kt)
   - [ForecastHistoryActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt)
   - [DailyAccuracyAdapter.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/DailyAccuracyAdapter.kt)
   - [WeatherObservationsActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt)
2. **Desktop**:
   - `StatisticsWindow.kt`
   - `ForecastHistoryWindow.kt`
   - `ObservationsWindow.kt`

---

## 8. Verification & Testing

### 8.1 Automated Tests
- Run all existing unit tests in `:shared` and `:app` modules to ensure there are no regressions.
- Add new unit tests in `TempUtilsTest.kt` verifying Celsius conversions.
- Add unit tests in `TemperatureLabelResolverTest.kt` and `ForecastEvolutionGeometryTest.kt` verifying Celsius-formatted output.
- Run desktop UI tests in `DesktopUiTest.kt`.

### 8.2 Manual Verification
- Deploy the widget to the Android emulator, toggle Celsius in Settings, and verify that the widget, statistics view, and history view redraw immediately in Celsius.
- Start the Desktop app, open settings, toggle Celsius, and verify the header, popup graphs, tray icon, and IPC server genmon output display in Celsius.
