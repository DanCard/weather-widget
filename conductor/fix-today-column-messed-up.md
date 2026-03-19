# Fix Today Column "Messed Up" Visuals (Final)

The Today column in the daily forecast displays "messed up" visuals (missing high label, invisible bars) primarily when the forecast high is missing (common with NWS in the afternoon) and our observation logic fails to find the high from our own recorded history.

## Objective
Ensure the Today column always shows a valid high/low range by correctly querying all recorded observations for today and triggering a background refresh when data is missing.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`**: Helper for resolving observed temperatures.
- **`app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`**: Logic for calculating Today's triple-line values.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`**: Prepares data for the daily graph.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`**: Manages the daily view and requests refreshes.
- **`app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`**: Renders the daily bars on the canvas.

## Implementation Steps

### 1. Correct Observation Logic in `ObservationResolver`
Add a new method `resolveDailyObservedExtremes` to find the maximum and minimum temperatures from *all* recorded temperatures today for the current source (including GENERIC_GAP fallback).

### 2. Update `DailyActualsEstimator`
Update `calculateTodayTripleLineValues` to accept the full list of today's observed temperatures (or the pre-calculated extremes) and use them for the "Observed" (orange) bar. This ensures that if we recorded a high of 85° at 2 PM, we still show it at 5 PM when it's cooled down.

### 3. Trigger Data Retrieval in `DailyViewHandler`
Update `DailyViewHandler.updateWidget`:
- After preparing `days`, check if Today's data is missing any extreme (High or Low).
- If anything is missing, call `requestMissingActualsRefresh` with a "today" reason suffix. This triggers a background sync to try and "retrieve" the missing information.

### 4. Fix Drawing Bug in `DailyForecastGraphRenderer`
In the `lowY != null` block for Today's rendering:
- Use `effectiveFHighY` instead of `fHighY` in the `canvas.drawLine` call for the blue bar (`todayTripleBluePaint`). This ensures a minimum 6dp height even if the forecast high matches the low.

## Verification & Testing
### Automated Tests
- Add a unit test to `ObservationResolverTest.kt` verifying that it correctly finds the max/min of a list of temperatures for the day.
- Add a unit test to `DailyViewLogicTest.kt` simulating missing forecast data but having recorded observations, and verify the Today column shows the correct high.

### Manual Verification
- Deploy to emulator.
- Verify that the Today column shows a correct high even if the NWS daily forecast has removed it (by seeing the high from our own recorded observations).
- Confirm that a background refresh is triggered if both the forecast and observations are missing Today's high.
