# Plan - Align Desktop and Emulator Temperature Delta Calculations

## 1. Problem Analysis
- **Symptom**: The temperature delta displays differently on Desktop (+5.1) vs. Emulator (+3.3), despite having the same forecast and observation data.
- **Root Cause**: 
  - On the Android widget, the repository fetches raw observations. The widget UI then calls `CurrentTemperatureResolver.resolve` passing the raw observation temperature. It calculates the delta (`appliedDelta = rawObservedTemp - forecastAtObsTime`) and uses it to compute the final `displayTemp = forecastNow + appliedDelta`.
  - On Desktop, `DesktopWeatherRepository` was performing delta-correction internally in `loadCached` and `refresh`, storing the *corrected* temperature in `ForecastResult.currentTemp`.
  - However, the Desktop UI (`Main.kt`) was *also* calling `CurrentTemperatureResolver.resolve` but passing `lastObservedTemp = forecast.currentTemp` (which was already corrected).
  - This resulted in double-resolution on Desktop, adding the forecast trend/change between the observation time and now twice:
    $$\Delta_{\text{desktop}} = (O - F_{\text{obs}}) + (F_{\text{now}} - F_{\text{obs}})$$
    With $O - F_{\text{obs}} = +3.3$ and $F_{\text{now}} - F_{\text{obs}} = +1.8$, this yielded $+5.1$ instead of $+3.3$.

## 2. Refactoring Steps
- **Unify Data Model**: Added `appliedDelta` directly to the shared `ForecastResult` data class to store the pre-resolved delta temperature alongside the resolved current temperature.
- **Refactor Repository Layer**:
  - Modified `DesktopWeatherRepository` to resolve the current temperature and delta using the shared `CurrentTemperatureResolver` and `ActualsAggregator` helpers, matching Android.
  - Calculated both `currentTemp` and `appliedDelta` in the repository and exposed them via `ForecastResult`.
- **Align UI Layer**:
  - Simplified the Desktop UI (`Main.kt`) to read `forecast.currentTemp` and `forecast.appliedDelta` directly from `ForecastResult` instead of redundantly invoking the resolver.
- **Diagnostics/Logging**:
  - Connected the shared `CurrentTemperatureResolver.dbLogger` to the SQLite `app_logs` database on Desktop to capture diagnostic resolution traces.

## 3. Verification Plan
1. **Unit Tests**:
   - Fix the test station ID in `DesktopWeatherRepositoryTest.kt` (changing `NWS_BLEND` to a normal station ID like `STATION_A` so it isn't filtered out by the blending builder).
   - Run `./gradlew :desktop:cleanTest :desktop:test --tests "com.weatherwidget.desktop.DesktopWeatherRepositoryTest"` to verify the repository logic.
   - Run the full project test suite (`./gradlew test`) to ensure no regressions in Android/shared modules.
2. **Manual verification**:
   - Build and start the desktop app to confirm the delta text matches the expected $+3.3$ value.
