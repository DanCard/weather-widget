# Fix: Current temp changes when toggling view mode

## Context

Tapping the current temperature (top-left) toggles between DAILY and TEMPERATURE views. The displayed current temp changes because TemperatureViewHandler passes `smoothedForecasts` to `CurrentTemperatureResolver.resolve()` but DailyViewHandler does not. The smoothed curve produces slightly different interpolated values than raw hourly data.

**Goal**: Make DailyViewHandler also use `smoothedForecasts` so the current temp is identical across all views.

## Plan

### 1. Extract smoothing computation into a shared utility

The smoothing logic (TemperatureViewHandler lines 324-341) groups hourly forecasts by time, selects the preferred source, and runs `GraphRenderUtils.smoothValuesPreservingGlobalExtrema`. Extract into a shared function:

```kotlin
fun computeSmoothedForecasts(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    smoothIterations: Int,
): Map<Long, Float>
```

### 2. Add `smoothedForecasts` parameter to DailyViewHandler.updateWidget

**File**: `DailyViewHandler.kt`
- Add `smoothedForecasts: Map<Long, Float>? = null` parameter to both `updateWidget` overloads (lines 105 and 137)
- Pass it to `CurrentTemperatureResolver.resolve()` at line 237

### 3. Compute and pass `smoothedForecasts` in WidgetIntentRouter.refreshDailyView

**File**: `WidgetIntentRouter.kt` — `refreshDailyView()` (line 743)
- Already has `hourlyForecasts`, `displaySource`, and `zoom`
- Compute `smoothedForecasts` using the extracted function
- Pass to `DailyViewHandler.updateWidget()` at line 793

### 4. Use extracted function in TemperatureViewHandler

**File**: `TemperatureViewHandler.kt`
- Replace inline smoothing logic (lines 324-341) with call to the extracted function

## Files to modify

1. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` — extract smoothing, use shared function
2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` — add `smoothedForecasts` param, pass to resolver
3. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` — compute smoothed forecasts in `refreshDailyView`, pass to DailyViewHandler

## Testing

No new automated tests needed. Existing coverage is sufficient:
- `CurrentTemperatureResolverTest.resolve uses smoothedForecasts when provided` — validates resolver behavior with smoothed data
- `TemperatureInterpolatorTest` — validates smoothed interpolation
- `GraphRenderUtilsTest` — validates the smoothing algorithm
- The extracted function is a thin wrapper around already-tested code

## Verification

1. `./gradlew testDebugUnitTest` — existing tests pass
2. On emulator: toggle between DAILY and TEMPERATURE views — current temp stays the same
