# Plan: Add Adaptive Coloring to Yesterday's Snapshot Bar

## Objective
Make the "yesterday" forecast snapshot bar (the yellow bar in the Today column) weather-adaptive. It should indicate cloud cover (grey bottom) and rain chance (blue bottom) exactly like regular forecast bars. For 100% cloudy/rainy days, it will be fully grey/blue. For sunny or mixed days, its top color will remain the distinctive bright yellow (`#FFFF00`).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`: Passes snapshot data up the chain.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Resolves the snapshot data.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Draws the graph lines and adaptive segments.

## Implementation Steps

1.  **Update `DailyActualsEstimator.kt`**
    *   Add `snapshotIconRes: Int?` to the `TodayTripleLineValues` data class.
    *   Add `snapshotIconRes` as a parameter to `calculateTodayTripleLineValues` and map it to the return object.

2.  **Update `DailyViewLogic.kt`**
    *   In `prepareGraphDays`, when selecting the `snapshot` for today, use `DailyForecastIconResolver.resolveIcon` to determine its `snapshotIconRes` using the snapshot's probability fields.
    *   Pass this `snapshotIconRes` into the `calculateTodayTripleLineValues` call.

3.  **Update `DailyForecastGraphRenderer.kt`**
    *   Add `snapshotIconRes: Int? = null` to the `DayData` data class.
    *   In `drawWeatherAdaptiveBar`, modify `topPaint` to inherit its color directly from the passed `paint.color` instead of hardcoding `split.topColor`. This allows us to pass in a bright yellow paint and preserve it on top of mixed bars.
    *   In `drawTodayTripleBar`, refactor the snapshot drawing logic:
        *   Determine `isSunny`, `isRainy`, `isMixed` from `day.snapshotIconRes`.
        *   Calculate its base color using `WeatherConditionColors.forecastColor`.
        *   If the result is `FORECAST_SUNNY` (or if there's no icon), override it with `paints.todaySnapshotYellowPaint.color` to preserve the snapshot's distinct brightness.
        *   Fetch a dynamically colored paint (`paints.todayForecastForColor(sCondColor)`).
        *   Create a temporary `snapshotDay` copy of `DayData` populated with the snapshot's weather flags.
        *   Call `drawWeatherAdaptiveBar` using the new paint and `snapshotDay`.

## Verification & Testing
- Deploy to emulator.
- Observe the "Today" column's snapshot bar.
- If yesterday forecasted mixed rain for today, the bottom should be blue and the top bright yellow.
- If yesterday forecasted 100% rain, the bar should be solid blue.