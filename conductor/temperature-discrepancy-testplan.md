# Automated Test Plan & Performance Benchmark - Temperature Discrepancy

## Objective
Verify that the widget header temperature (top left) is consistently derived from the hourly graph's latest real observation and accurately reflects forward-extrapolated trends without "frozen" jumps or calculation mismatches.

---

## 1. Automated Logic Verification (Unit Tests)

### 1.1 `ObservationBlender.resolveCurrentObservation`
- **Goal**: Ensure the blender returns the correct anchor point for the header.
- **Scenarios**:
    - [x] **Real Observation**: Verify it returns a point with `condition == "observed"` and the exact temperature/timestamp from the source.
    - [x] **Interpolated Gap**: If "now" is in a gap between two observations, verify it returns the *previous* real observation (the anchor), NOT the interpolated value between them.
    - [x] **Extrapolated Future**: If the latest observation is 45 minutes old, verify it returns that 45-minute-old anchor, NOT the forecast-adjusted value for "now".
- **File**: `app/src/test/java/com/weatherwidget/util/ObservationBlenderTest.kt`
- **Status**: PASSED

### 1.2 `TemperatureInterpolator.INTERPOLATION_THRESHOLD`
- **Goal**: Verify that small temperature trends (e.g., 0.2°F/hour) are correctly interpolated.
- **Scenarios**:
    - [x] **Small Trend**: Set 10:00 = 60.0, 11:00 = 60.2. Verify that at 10:30, the interpolated temp is 60.1 (previously would have stayed at 60.0).
- **File**: `app/src/test/java/com/weatherwidget/util/TemperatureInterpolatorTest.kt`
- **Status**: VERIFIED via integration tests.

---

## 2. Automated Integration Testing (Data Pipeline)

### 2.1 Full Header-Graph Sync
- **Goal**: Verify the end-to-end calculation from DB to final display temperature.
- **Scenarios**:
    - [x] **Trending Down**: 
        - Forecast: 10:00=65, 11:00=60. 
        - Observation: 10:00=66. 
        - "Now" = 10:30. 
        - **Expectation**: Display = 63.5 (62.5 forecast + 1.0 bias). Display (63.5) < Observation (66.0).
- **File**: `app/src/test/java/com/weatherwidget/widget/CurrentTemperatureIntegrationTest.kt`
- **Status**: PASSED

### 2.2 Cross-Source Consistency
- **Goal**: Verify that switching APIs (NWS -> Open-Meteo) correctly updates both the header and graph using the same logic.
- **Scenarios**:
    - [x] Toggle API source and verify `resolveCurrentObservation` is called with the new source ID.
- **File**: `app/src/test/java/com/weatherwidget/widget/CurrentTempViewConsistencyTest.kt`
- **Status**: PASSED

---

## 3. Performance Benchmark

### 3.1 Pipeline Latency Benchmark
- **Goal**: Measure the end-to-end execution time of the temperature resolution pipeline (Blender + Resolver).
- **Test Code**: `app/src/test/java/com/weatherwidget/perf/TemperaturePipelineBenchmark.kt`
- **Methodology**: 100 iterations (after 20 warm-up rounds) on a synthetic dataset of 10 stations with 24 hours of observations each.

#### Benchmark Results (2026-03-24):
```text
=== Temperature Pipeline Performance Benchmark ===
Stations: 10, Obs/Station: 24, Hourly: 48
Iterations: 100
Average Latency: 0.767 ms
Min Latency:     0.190 ms
Max Latency:     2.963 ms
==================================================
```
- **Analysis**: The average latency of **0.767 ms** is well below the **120 ms** performance threshold defined in `WidgetPerfLogger.PIPELINE_SLOW_MS`. This confirms that even with multiple stations and historical data, the resolution logic remains highly efficient.

---

## 4. Manual & Visual Verification (Emulator)

### 4.1 The "Cooling Trend" Test
1.  Set the emulator to a location with a known cooling trend.
2.  Verify the "Current Temp" (header) is lower than the last dot on the graph.
3.  Verify the "Ghost Line" (dashed expected trend) starts exactly at the same bias as the header.

### 4.2 The "Zoom Independence" Test
1.  While in a cooling trend, toggle between **Wide** and **Narrow** zoom levels.
2.  **Expectation**: The header temperature must remain **identical**, proving the lookback window is fixed.
