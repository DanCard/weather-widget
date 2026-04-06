# Session Log: Fix Daily View Current Temperature Jump

## Date
April 5, 2026

## Prompts & Interactions
**1. User (Initial Prompt):**
> "emulator: current temperature changes when going to temperature graph from daily view.  When going back to daily view, temperature changes again significantly after a few minutes.  Current temperature should stay somewhat constant.  Algorithm and data should be the same when on daily view or temperature graph."

**2. Assistant (Plan Proposal):**
> Proposed passing `observation?.temperature` and `observation?.observedAt` into `DailyViewHandler.updateWidget` in `WidgetRenderer.kt`.

**3. User (Feedback):**
> "What about tests? Should we remove fallback?"

**4. Assistant (Revised Plan):**
> Updated the plan to remove the fallback logic from `DailyViewHandler` and `DailyViewLogic`, and update the corresponding tests to explicitly pass the observations.


## Investigation
- Searched for `updateWidget` in the codebase to trace how the current temperature is passed to the daily and hourly view handlers.
- Traced `WidgetIntentRouter.kt` and `WidgetRenderer.kt`. 
- Discovered that during opportunistic/background updates triggered by `WidgetRenderer.updateWidgetWithData()`, the pre-calculated `observation` object was passed to `TemperatureViewHandler`, `PrecipViewHandler`, and `CloudCoverViewHandler` but explicitly omitted from the `DailyViewHandler.updateWidget()` call.
- Because `DailyViewHandler` received `lastObservedTemp = null`, it fell back to using `ObservationResolver.resolveObservedCurrentTemp()` which resolved a raw observation. This difference in resolution algorithm caused the current temperature to jump to a different value when the widget refreshed in the background after the user switched back to the daily view.

## Implementation
1. **WidgetRenderer.kt**: Updated the `ViewMode.DAILY` branch to explicitly pass `lastObservedTemp = observation?.temperature` and `observedAt = observation?.observedAt`.
2. **DailyViewHandler.kt**: Removed the `ObservationResolver.resolveObservedCurrentTemp` fallback block. `CurrentTemperatureResolver.resolve` now strictly uses the `lastObservedTemp` and `observedAt` provided by the caller.
3. **DailyViewLogic.kt**: Removed the internal `ObservationResolver` fallback for `currentTemp` in both `prepareGraphDays` and `prepareTextDays`. Passed `currentTemp` straight through to `calculateTodayTripleLineValues`.
4. **DailyViewHandlerTest.kt**: Updated failing tests (`prepareGraphDays includes snapshot and current temp for today`, `updateWidget daily header shows delta when precip is absent`, `updateWidget daily header shows delta when precip is visible`) to explicitly provide `lastObservedTemp` and `observedAt` to `DailyViewHandler.updateWidget` and `DailyViewLogic.prepareGraphDays`.
5. **DailyViewHandlerTodayDropIntegrationTest.kt**: Updated the setup blocks to pass `lastObservedTemp = 75f` and `observedAt = now` to `DailyViewHandler.updateWidget`.

## Verification
- Fixed an initial Kotlin compilation error (accidentally deleted `resolveStartMs` assignment).
- Identified and fixed 3 failing unit tests and 1 failing integration test caused by the removed internal fallback.
- Validated that all 698 unit tests passed successfully.
- Code was committed and pushed locally.

## Detailed Implementation Plan

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
