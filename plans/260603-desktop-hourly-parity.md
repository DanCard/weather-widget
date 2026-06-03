# Desktop Hourly Graph Parity Plan

This plan outlines the steps to bring the Desktop Hourly Temperature graph to parity with the Android implementation, focusing on the "actual" temperature line, lookback behavior, and visual styling.

## 1. Data Pipeline (`DesktopWeatherRepository.kt`)
- [x] Update `loadCached` to fetch observations for the past 48 hours.
- [x] Populate `rawObservations` in `ForecastResult` from the database.
- [x] Ensure `refresh` includes `rawObservations` in the returned result.

## 2. Graph Logic & lookback (`TemperatureGraph.kt`)
- [x] Implement a default 8-hour lookback when `startOffsetHours` is 0 (matching Android's WIDE zoom).
- [x] Filter and sort `observations` to create a continuous "actual" line data series.
- [x] Calculate graph scaling (min/max temp) using both forecast and actual data points to ensure all visible lines fit within the bounds.

## 3. Visual Parity (`TemperatureGraph.kt`)
- [x] **Actual Line:** Implement a solid pink line (`COLOR_ACTUAL`) using the observation data.
- [x] **Ghost Forecast:** Render the historical portion of the forecast line as dashed and muted (50% alpha) to distinguish it from the "actual" truth.
- [x] **Forecast Transition:** Ensure the transition from dashed (past) to solid (future) occurs at the current time index.
- [x] **"Now" Indicator:** Align the vertical "Now" guide and target circle with the current timestamp precisely.
- [ ] **Advanced Styling (Optional/Future):**
    *   Port Catmull-Rom smoothing for the actual line (currently using basic linear/cubic Compose paths).
    *   Add point markers for real observations vs. interpolated values.

## 4. Interaction
- [x] Ensure `hourlyOffset` from `DesktopConfig` correctly shifts the visible window, allowing the "actual" line to be viewed by navigating into the past.

## 5. Verification
- [ ] Run `:desktop:run` and verify the pink line appears for the past 8+ hours.
- [ ] Verify the dashed forecast line overlaps or tracks near the actual line in the past.
- [ ] Confirm the future forecast transitions to a solid gradient line.
