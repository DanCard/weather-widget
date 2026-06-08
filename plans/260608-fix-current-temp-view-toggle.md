# Fix: Current temperature changes when navigating to Hourly Temperature View

## Background & Motivation
The user reported that the current temperature is incorrect on the emulator and Samsung devices, and it changes when toggling from the Daily View to the Hourly Temperature View (but works fine on the Pixel 7 Pro).

Upon investigation, it was discovered that `WidgetIntentRouter.kt` contains a discrepancy in how it resolves the fallback observation for the current temperature between `refreshDailyView` and `refreshGraphView` (via `updateHourlyViewWithData`). 

In `updateHourlyViewWithData` (which renders the Hourly Temperature View upon navigation), the code correctly uses a fallback if `CurrentTempResolver.resolveGraphStyleCurrentTemp` returns null:
```kotlin
val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
```

However, in `refreshDailyView` (which renders the Daily View upon navigation), the code omits this fallback entirely and passes `graphStyleObs?.temperature` directly into `ObservationData`. If the graph-style IDW interpolation fails or has gaps (which might happen on specific devices or due to missing data), `graphStyleObs` is null, causing `DailyViewHandler` to evaluate with a null current temperature, while `updateHourlyViewWithData` uses a valid fallback. This causes the temperature to jump when toggling views.

## Scope & Impact
- This fix targets a specific omission in `WidgetIntentRouter.kt` within the `refreshDailyView` method.
- It will unify the current temperature resolution path across view toggles, ensuring both Daily and Hourly views display the exact same temperature under identical conditions.
- No new features are introduced; this aligns existing logic with the implementation in `WidgetRenderer.kt` and `refreshGraphView`.

## Proposed Solution
Modify `WidgetIntentRouter.kt` to include the `ObservationResolver` fallback in `refreshDailyView`.

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

**Changes**:
```kotlin
        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTemp(
            repository = repository,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts,
            now = now,
        )

        val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(ctCurrentTemps, displaySource)

        val smoothedForecasts = computeSmoothedForecasts(
            hourlyForecasts, displaySource
        )

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherData = WeatherData(
                weatherList = finalWeatherList,
                forecastSnapshots = forecastSnapshots,
                hourlyForecasts = hourlyForecasts,
                currentTemps = ctCurrentTemps,
                dailyActualsBySource = finalDailyActuals,
            ),
            observationData = ObservationData(
                lastObservedTemp = observation?.temperature,
                observedAt = observation?.observedAt,
                smoothedForecasts = smoothedForecasts,
            ),
```

## Alternatives Considered
- Refactoring the entire data loading strategy to a central resolver: Deferred, as this issue requires a targeted fix for parity between view navigation methods. The current fix is minimal and directly addresses the discrepancy.

## Implementation Plan
1. Edit `WidgetIntentRouter.kt` to apply the `ObservationResolver.resolveObservedCurrentTemp` fallback in `refreshDailyView`.
2. Update the `ObservationData` instantiation to use `observation?.temperature` and `observation?.observedAt`.

## Verification & Testing
1. Compile the app (`./gradlew testDebugUnitTest`).
2. Run on emulator: Toggling between the Daily view and Hourly Temperature view should maintain a consistent, correct current temperature.
3. Validate that the current temperature matches the expected observed temp even when `graphStyleObs` fails to resolve.
