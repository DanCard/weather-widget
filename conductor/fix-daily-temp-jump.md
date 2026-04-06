# Fix Temperature Jump on View Transition and Background Update

## Background & Motivation
The user reported that the current temperature displayed on the widget changes significantly when navigating between the Daily view and the Hourly Temperature graph, and then changes again after a few minutes when returning to the Daily view.

The root cause is a discrepancy in how the "current temperature" observation is passed to the different view handlers during standard updates:
1. **Navigation**: When the user taps to switch views, `WidgetIntentRouter` computes a blended/interpolated `graphStyleObs` and passes it directly to the view handlers. This ensures consistency *during* the transition.
2. **Background/Opportunistic Updates**: These updates are routed through `WidgetRenderer.updateWidgetWithData()`. While `WidgetRenderer` correctly calculates the advanced `observation` (using `ObservationBlender` or repository interpolation), it explicitly passes `lastObservedTemp = observation?.temperature` and `observedAt = observation?.observedAt` to `TemperatureViewHandler`, `PrecipViewHandler`, and `CloudCoverViewHandler`. **However, it fails to pass these parameters to `DailyViewHandler.updateWidget`.**
3. **The Jump**: Because `DailyViewHandler` receives `lastObservedTemp = null` from `WidgetRenderer`, it falls back internally to a simpler `ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)` method. This raw fallback often differs from the blended `graphStyleObs`, causing the temperature to abruptly "jump" back to a raw value a few minutes after the user returns to the Daily view.

## Proposed Solution
We need to align the `ViewMode.DAILY` branch in `WidgetRenderer.updateWidgetWithData` with the other view modes by passing the pre-calculated `observation` down to `DailyViewHandler.updateWidget`.

Additionally, per the feedback, we should remove the internal fallback logic (`ObservationResolver.resolveObservedCurrentTemp`) from `DailyViewHandler.kt` and `DailyViewLogic.kt`. This enforces architectural consistency: the caller (like `WidgetRenderer` or `WidgetIntentRouter`) must explicitly provide the resolved observation (whether blended or raw), preventing the Daily view from quietly reverting to a different calculation method. Tests will also need to be updated to pass `lastObservedTemp` explicitly where needed.

## Implementation Steps
1. **Modify `WidgetRenderer.kt`**:
   - Open `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`.
   - Locate the `ViewMode.DAILY` branch inside `updateWidgetWithData` (around line 187).
   - Add `lastObservedTemp = observation?.temperature` and `observedAt = observation?.observedAt` to the `DailyViewHandler.updateWidget` function call.

```kotlin
            ViewMode.DAILY -> {
                DailyViewHandler.updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    weatherList,
                    forecastSnapshots,
                    hourlyForecasts,
                    currentTemps,
                    dailyActualsBySource,
                    repository,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    startupToken = startupToken,
                )
            }
```

2. **Remove Fallbacks in `DailyViewHandler.kt` and `DailyViewLogic.kt`**:
   - In `DailyViewHandler.kt`, remove `val resolvedObs = if (lastObservedTemp != null) ... else { ObservationResolver... }`. Instead, use `lastObservedTemp` and `observedAt` directly for `CurrentTemperatureResolver.resolve()`.
   - In `DailyViewLogic.kt` (`prepareGraphDays` and `prepareTextDays`), remove the `currentTemp ?: ObservationResolver...` fallback and just use `currentTemp` directly for the "Today" bar rendering.

3. **Update Tests**:
   - Review tests in `DailyViewHandlerTest.kt` and `DailyViewLogicTest.kt`.
   - Ensure tests that assert on the "Today" column or the current temperature header correctly pass `lastObservedTemp` and `observedAt` explicitly, since the internal fallback from `currentTemps` will be removed.

## Verification
- Deploy to emulator.
- Set view to Daily, note the temperature.
- Switch to Hourly Temperature graph, note the temperature (should match).
- Wait for a background update (or trigger an opportunistic update by turning the screen off and on).
- The temperature should remain stable and logically consistent, without jumping back to a raw unblended value.
