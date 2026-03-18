# Fix Noisy Hourly Temperature History

## Objective
Address the "samsung: temperature hourly history looks noisy" issue by resolving data multiplexing. The widget currently aggregates actual observations from *all* nearby weather stations into a single timeline. Because different stations (e.g., airport vs. personal weather station) have different micro-climates, plotting them together creates massive zig-zagging noise.

## Background & Motivation
In `TemperatureViewHandler.kt`, `getObservationsInRange` returns observations from multiple stations. The code filters out different *sources* (like Open-Meteo when viewing NWS), but does not filter to a single *station*. The `buildHourDataList` function then blindly injects all these mixed readings into the hourly timeline. This creates a noisy, erratic history curve.

## Proposed Solution
1. **Single Station Filtering:** In `TemperatureViewHandler.kt`, identify a primary station (e.g., the closest station by `distanceKm` that has readings) and filter `filteredObs` to only include data from this `primaryStationId` before passing it to `buildHourDataList`.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

## Implementation Steps
1. Modify `TemperatureViewHandler.kt` around line 178 to filter by a single station:
   ```kotlin
   // Find the closest station to act as the primary source of truth
   val primaryStationId = filteredObs.minByOrNull { it.distanceKm }?.stationId
   val singleStationObs = if (primaryStationId != null) {
       filteredObs.filter { it.stationId == primaryStationId }
   } else {
       filteredObs
   }
   val hourData = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource, zoom, singleStationObs)
   ```

## Verification & Testing
- Deploy to an emulator or device.
- Ensure the history line does not zig-zag wildly between station readings.
- The path should still render linearly (no smoothing applied), but the values should be stable since they come from a single station.
