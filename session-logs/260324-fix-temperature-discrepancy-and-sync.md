# Session Notes: Fix Temperature Discrepancy and Sync (Header vs Graph)
**Objective:** Resolve the issue where the widget's current temperature (header) and hourly graph's last observed temperature were out of sync, especially during cooling/warming trends.

## 1. Problem Analysis & Root Causes
Through investigation of the codebase and `app_logs`, four primary issues were identified:
- **Strict Anchor Violation:** The header was picking "interpolated" or "forecast_extrapolated" points from the `ObservationBlender` as its starting point. This led to incorrect bias calculations because the resolver didn't know these weren't real raw observations.
- **Inconsistent Resolution Paths:** The manual refresh (`WidgetIntentRouter`) and the periodic widget update (`WeatherWidgetProvider`) used different logic to resolve the "current" temperature, leading to visual flickering and jumps.
- **Frozen Interpolation:** An internal `INTERPOLATION_THRESHOLD` of 1.0°F meant that if the forecast dropped by < 1.0°, the interpolation logic would simply return the previous hour's value, ignoring the trend.
- **Stale Preference:** `ObservationResolver` had a hardcoded preference for `NWS_BLEND` station IDs, which sometimes caused it to pick an older aggregate blend over a newer, more accurate raw station reading.

## 2. Implementation Details

### A. Strict Data Synchronization
- **`ObservationBlender.kt`**: Modified `resolveCurrentObservation` to strictly filter for `condition == "observed"`. This ensures the header is always anchored to a real measurement at a specific time, allowing the `CurrentTemperatureResolver` to project from that "true" point.
- **`WeatherWidgetProvider.kt`**: Refactored `updateWidgetInternal` to use `ObservationBlender.resolveCurrentObservation` (matching the router). This unifies the data source for all update triggers.

### B. Smoothing and Lookback
- **`TemperatureInterpolator.kt`**: Reduced `INTERPOLATION_THRESHOLD` from `1.0f` to `0.1f`. This ensures the current temperature header reflects subtle forecast trends immediately.
- **`WidgetIntentRouter.kt`**: Fixed the observation lookback window to a constant **12 hours** in `resolveGraphStyleCurrentTemp`. This prevents the header from falling back to a different data path when the user zooms into a narrow window.

### C. Logic Refinement
- **`ObservationResolver.kt`**: Updated `resolveObservedCurrentTemp` to prioritize the newest timestamp above all else. If timestamps match, it still prefers `NWS_BLEND` for representativeness.

## 3. Verification & Performance

### Automated Tests
- **Integration Test:** Added `current temperature correctly trends down when forecast is cooling` to `CurrentTemperatureIntegrationTest.kt`.
- **Unit Tests:** Updated `ObservationBlenderTest.kt` to verify the strict "observed" anchor requirement.
- **Consistency Test:** Verified that IDW-blended data remains consistent across view toggles in `CurrentTempViewConsistencyTest.kt`.

### Performance Benchmark
Implemented `TemperaturePipelineBenchmark.kt` to measure the cost of the unified blending logic.
- **Average Latency:** **0.767 ms**
- **Min/Max:** 0.19 ms / 2.96 ms
- **Verdict:** Logic is highly efficient; well within the 120ms "slow pipeline" threshold.

## 4. Artifacts Created
- `conductor/temperature-discrepancy-testplan.md`: Comprehensive test plan for future regressions.
- `app/src/test/java/com/weatherwidget/perf/TemperaturePipelineBenchmark.kt`: Automated latency benchmark.

## 5. Next Steps
- Monitor the "Ghost Line" behavior on the emulator during the next NWS fetch cycle to ensure the transition remains smooth.
- The 900ms "startup refinement" is now much more consistent since both Phase 1 (forecast) and Phase 2 (refined) use the same interpolation math.
