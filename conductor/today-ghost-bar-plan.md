# Ghost Bar for Today's Maximum Temperature

## Objective
Implement the "Ghost Bar" UI concept for the Today column in the Daily Forecast view. This ensures that while the solid red bar acts as a thermometer for the *current* temperature, users can still see a semi-transparent visual indicator (a "ghost bar") of the actual highest temperature reached earlier in the day.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt` (Logic)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (Mapping)
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (Rendering)

## Implementation Steps

### 1. Update Data Models
- **`TodayTripleLineValues`**: Add a new property `val trueActualHigh: Float? = null` to hold the actual observed high temperature.
- **`DayData`**: Add a new property `val trueActualHigh: Float? = null` to the `DailyForecastGraphRenderer.DayData` data class.

### 2. Populate True Actual High
- **`DailyActualsEstimator.kt`**: Inside `calculateTodayTripleLineValues`, assign `trueActualHigh = actual?.highTemp`. The `observedHigh` will remain the current temperature.
- **`DailyViewLogic.kt`**: When constructing `DayData` for today, map `tripleValues.trueActualHigh` to the new `trueActualHigh` field in `DayData`.

### 3. Update the Renderer
- **Layout Computation**: In `computeLayout`, update the `allTemps` flatMap to include `it.trueActualHigh` so the graph's overall `maxTemp` properly scales to fit the ghost bar.
- **Paint Setup**: In `getPaintSet` inside `PaintSet`, add a new `todayObservedGhostPaint`. It should be constructed exactly like `todayObservedRedPaint` but with a lowered opacity (e.g., `alpha = 76` for ~30% opacity).
- **Drawing**: In `drawTodayTripleBar`, check if `day.trueActualHigh` is present and greater than the solid bar's top (`day.high`). If so, calculate the `ghostHighY` and draw a line extending from `ghostHighY` down to `obsHighY` using the new `todayObservedGhostPaint`.

## Verification & Testing
- Unit tests: Update any `DailyActualsEstimatorTest` failures caused by adding the new property.
- Manual testing (Emulator): Simulate a scenario where the actual high (e.g., 85°) is higher than the current temperature (e.g., 70°). Verify that the solid red bar reaches 70° and a semi-transparent red line continues up to 85°.