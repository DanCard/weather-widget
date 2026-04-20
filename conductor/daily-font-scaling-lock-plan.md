# Plan: Lock Daily Font Scaling

## Objective
Prevent the fonts and UI elements in the Daily forecast view from enlarging when the widget is resized vertically past 250dp.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains the `heightScaleFactor` calculation that applies a `1.05f` multiplier for tall widgets.
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererSizingTest.kt`: Contains a test checking the `1.05f` scaling behavior.

## Implementation Steps
1. **Update `DailyForecastGraphRenderer.kt`:**
   - Modify the `heightScaleFactor` calculation to remove the `1.05f` multiplier for heights `>= 250f`. 
   - The new logic will scale down for `< 150f` (0.92f) and use `1.0f` for all taller sizes.
2. **Update `DailyForecastGraphRendererSizingTest.kt`:**
   - Remove or update the test `forecast temperature label size uses larger scale for tall widgets` to reflect that tall widgets now use a `1.0f` scale factor, preventing test failures.

## Verification & Testing
- Run unit tests to ensure `DailyForecastGraphRendererSizingTest` passes.
- Build and deploy the widget to an emulator or device.
- Resize the widget vertically and visually verify that the fonts and bars do not enlarge when crossing the 250dp threshold.