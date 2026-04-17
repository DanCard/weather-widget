# Plan: Forecast Bar Color Experiment

The visual discrepancy between Monday (62% rain, blue bar) and Tuesday (58% rain, grey bar) is due to a 60% threshold in `WeatherIconMapper.kt` that determines whether to use a "Chance Rain" icon (Blue bar) or a "Slight Chance Rain" icon (Grey bar).

## Phase 1: Experiment Gallery (First Step)

### Changes
- **Create Experiment Drawables**: Create three new drawables showing the "slight chance" icons with the proposed blue bars:
    - `ic_weather_experiment_pc_slight_rain`
    - `ic_weather_experiment_pc_slight_rain_night`
    - `ic_weather_experiment_c_slight_rain`
- **Update `SettingsActivity.kt`**: Add these 3 icons to the `experimentIcons` list.
- **Update `strings.xml`**: Add labels for these 3 experiment icons.

### Verification
- Build and install the app on the Pixel device.
- Open Settings and capture a screenshot of the experiment gallery.
- Confirm the blue bars look correct for these conditions.

## Phase 2: Implementation (After Approval)

### Changes
- **Update `WeatherConditionColors.kt`**: Include the "slight chance" icons in the `CHANCE_RAIN_ICONS` set so they map to the Blue (`FORECAST_RAINY`) color in the widget.
- **Add Explicit Logging**:
    - Log the `precipProbability` and selected icon in `WeatherIconMapper.kt`.
    - Log the specific color choice (Blue vs. Grey) and the reason in `WeatherConditionColors.kt`.

### Verification
- Verify that Tuesday's bar on the widget is now blue.
- Audit Logcat for clear, non-ambiguous "Bar color decision" messages.
