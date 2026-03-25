# Plan: Fix 8-Second Actuals Graph Rendering Delay

## Objective
The goal is to eliminate the 8-second delay when drawing the "actual" (observed) temperature line on the hourly graph. Investigation has pinpointed the bottleneck to the `ObservationBlender.forecastTemperatureAt` function, which is called in a tight loop (~86,000 times) and performs expensive `atZone(ZoneId.systemDefault())` and `LocalDateTime` conversions on every iteration.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/util/ObservationBlender.kt`**: Contains the core IDW blending and extrapolation logic. This is the primary source of the performance regression.
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**: Coordinates the widget update. It currently performs some expensive logging and string formatting in the main render path.

## Implementation Steps

### 1. Refactor `ObservationBlender` to use Long Epoch Milliseconds
- **`forecastTemperatureAt`**:
    - Modify the function to accept and return `Long` (epoch millis) instead of `LocalDateTime` where possible.
    - Compare `targetTimestamp` (already a `Long`) directly against `forecast.dateTime` (already a `Long`).
    - Use binary search (`Collections.binarySearch` or similar) to find the `before` and `after` forecast points instead of linear `find`, `lastOrNull`, and `firstOrNull` calls.
    - Remove all calls to `atZone(ZoneId.systemDefault())` and `toLocalDateTime()` from the inner loop.
- **`buildStationTimeSeries`**:
    - Avoid `atZone` and `format` calls inside the interpolation loop.
    - Move debug string generation into a `lazy` or conditional block that only executes if the logger actually emits the line.
- **IDW Blending Loop**:
    - Optimize the `for (targetTs in candidateTimes)` loop. Since both `candidateTimes` and each station's `points` list are sorted by timestamp, use a pointer-based approach (or `binarySearch`) to find the nearest point for each station instead of $O(N)$ `minByOrNull`.

### 2. Optimize `TemperatureViewHandler` Logging
- **Station Breakdown**:
    - Move the generation of the `stationBreakdown` summary (which contains many `atZone` and `format` calls) into the `onBlendDebug` lambda so it only runs when debug logging is actually being collected.
- **Phase 2 Throttling**:
    - Ensure that Phase 2 (the full graph render) doesn't start if another update is already in progress.

### 3. Efficiency & Pre-calculation
- **Timezone Caching**:
    - If `ZoneId.systemDefault()` is still needed, fetch it once at the start of the blending process and reuse it.
- **Time String Pre-formatting**:
    - For logging `candidateTimes` or `alignedTimes`, pre-format the strings once if they will be reused across different stations.

## Verification & Testing
- **Performance Test**:
    - Add log markers around `ObservationBlender.blendObservationSeries` and `buildHourDataResult`.
    - Verify on a device that `buildHourDataMs` drops from ~8000ms to <100ms.
- **Functional Verification**:
    - Ensure the actual graph line still renders correctly, following the forecast trend and aligning with hourly labels.
    - Verify that IDW blending across multiple stations still produces smooth, accurate results.
- **Regression Test**:
    - Verify that "Current Temperature" (widget header) still correctly incorporates the observed delta.
