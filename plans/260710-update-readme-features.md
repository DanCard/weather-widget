# Plan - Update README with List of Features

The objective is to update the main `README.md` of the Weather Widget project with a clear, detailed, and comprehensive list of features, covering both the Android widget and the Linux desktop companion application.

## Proposed List of Features

1. **Multi-Source Weather Forecast Integration**
   - Seamless toggling between primary weather providers: NWS (US-only, official government data), Open-Meteo (global, no API key), and Silurian.
   - Hidden/configurable support for Tomorrow.io, WeatherAPI, Visual Crossing, and OpenWeatherMap (key-based).
   - Priority fallback logic to fetch from alternate sources if a primary source is unavailable or lacks coverage for the current location.

2. **Adaptive Android Widget Layouts**
   - Dynamically adapts its UI layout to fit any widget size from 1x1 up to 8+ columns and 2+ rows.
   - **1x1**: Show high/low extrema, weather condition icon, and current temp (if space allows).
   - **1x3 (Narrow/Horizontal)**: Shows yesterday, today, and tomorrow in clean text mode.
   - **2x3 (Graphical)**: Adds daily graphical temperature range bars.
   - **4+ Columns / 2+ Rows**: Adds full forecast graphs and visualizes up to 7+ days.
   - Generous touch targets for left/right navigation, view toggling, and API selection.

3. **Custom Graphical Renderers**
   - **Daily Graph**: Renders custom temperature bars. For past days, overlays 1-day-ahead forecast snapshots (yellow bars) next to actual observations for visual comparison.
   - **Hourly Graph**: Renders a smooth Bezier temperature curve with min/max labels, start/end values, and a vertical "Now" indicator line.
   - **Precipitation Graph**: Shows hourly chance of rain (0-100%) and expected rain volumes.
   - **Cloud Cover Graph**: Displays hourly cloud coverage percentage.
   - **Label Collision Avoidance**: Intelligent positioning algorithms prevent overlapping text elements.

4. **Current Temperature Interpolation & Units**
   - Real-time current temperature estimation by interpolating hourly forecast data points, saving battery by avoiding redundant network fetches.
   - Support for both Celsius and Fahrenheit, updating all labels and graphs automatically.

5. **Detailed Forecast Accuracy Tracking**
   - Captures daily 1-day-ahead forecast snapshots before an 8:00 PM cutoff.
   - Retrieves historical observations from NWS (with up to 5 nearby fallback stations for resilience).
   - Compares predictions vs. observations to compute detailed 30-day statistics: high/low error margins, directional temperature bias, and a 0-5 rating score.
   - **Forecast History Activity**: Inspect forecast evolution over time relative to actual weather.

6. **Battery & Power-Aware Sync System**
   - Separation of light UI updates (10-16 min) from heavy network operations.
   - Dynamic API fetch intervals based on battery levels (scaling from 60 min on charger down to 480 min/suspended under low battery).
   - WorkManager-based updating with work-stall recovery to prevent frozen widgets on devices with aggressive OEM battery-saving controls.

7. **Linux Desktop Companion App (Compose Multiplatform)**
   - System-tray temperature icon and tray context menu.
   - Graphical pop-up window showing hourly/daily graphs and details, mirroring the Android layout.
   - **XFCE Panel Integration (Genmon)**: High-performance IPC Server supplying clock-sized Pango markup weather summaries directly to the XFCE panel.
   - Single-instance enforcement (last-launch-wins) and autostart capabilities.

8. **Diagnostic Tools & Play Store Hardening**
   - Diagnostics UI showing persistent database logs (`app_logs`) to track API fetches, scheduler updates, and system events.
   - Explicit background location disclosures and a built-in privacy policy viewer.

## Implementation Steps

1. Read `README.md`.
2. Locate a suitable place to insert the "Features" section (e.g., right after "Overview").
3. Use `replace_file_content` to add the features list to `README.md`.
4. Run `git diff` to verify the modifications.
5. Commit the changes following the specified commit conventions.
