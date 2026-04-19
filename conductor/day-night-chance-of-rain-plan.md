# Plan: Day/Night Chance of Rain Labels on Daily Graph

## Objective
Enhance the daily forecast graph to distinctly display the day and night chance of rain using the following user-defined rules:
1. **Day Chance of Rain**: Displayed at the top of the column (above the high temperature).
2. **Night Chance of Rain**: Displayed at the bottom of the column (below the low temperature).
3. **Night Visibility Rules**: 
   - Only shown if the day chance of rain is *not* shown.
   - Tonight (Day 0) requires > 50% chance.
   - Each subsequent night increases the threshold by 5% (Tomorrow night > 55%, Day 2 > 60%, etc.).
4. **Font Scaling**: The font size for the rain labels will progressively shrink the further out the forecast is.
5. **Header**: The top header rain probability will remain unchanged (it will continue to use the 8-hour lookahead window to act as an "imminent threat" indicator).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Responsible for computing the label text and enforcing the visibility thresholds.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Responsible for rendering the labels on the canvas, handling collision detection, and scaling the fonts.

## Implementation Steps

### 1. Update `DayData` Model
- Modify `DayData` in `DailyForecastGraphRenderer.kt` to replace `dailyRainLabelText` with two specific fields: `dayRainLabelText: String?` and `nightRainLabelText: String?`.

### 2. Update `DailyViewLogic.kt`
- Modify `buildDailyRainLabel` (or create a new specialized function) to return a `Pair<String?, String?>` representing the day and night labels.
- **Direct NWS API Values**: For the calculations below, strictly use the direct NWS API period values (`ForecastEntity.daytimePrecipProbability` and `ForecastEntity.nighttimePrecipProbability`) rather than the hourly-derived `dayNightPrecip` values, ensuring Day 2+ rain chances exactly match the NWS 12-hour period predictions.
- **Day Label Logic**: Keep the existing formatting logic (e.g., showing amount if >= 99%, otherwise percent) using `daytimePrecipProbability`.
- **Night Label Logic**: 
  - Only evaluate if the day label is null.
  - Calculate the dynamic threshold: `val threshold = 50 + (daysFromToday * 5)`.
  - If `nighttimePrecipProbability > threshold`, set the night label to the formatted percent.

### 3. Update `DailyForecastGraphRenderer.kt`
- Update `drawDailyRainLabel` to handle both the top (day) and bottom (night) labels.
- **Top Placement (Day)**: Attempt to place `dayRainLabelText` above the high temperature label.
- **Bottom Placement (Night)**: Attempt to place `nightRainLabelText` below the low temperature label or icon.
- **Progressive Font Scaling**: Calculate a scale factor based on `daysFromToday` (e.g., `val scale = maxOf(0.5f, 1.0f - (day.daysFromToday * 0.08f))`). Apply this scale to `rainTextPaint.textSize` before drawing the labels so they get progressively smaller the further into the future they are.
- **Collision Detection**: Ensure both labels participate in the `drawnLabelBounds` collision detection to prevent overlapping with other UI elements.

## Verification & Testing
- **Robolectric Tests**: 
  - Update `DailyViewLogicTest.kt` (which runs under Robolectric) to verify the dynamic threshold math.
  - Add test cases ensuring that when `daytimePrecipProbability` is null or doesn't trigger a label, the `nighttimePrecipProbability` is evaluated against the `50 + (daysFromToday * 5)` threshold.
  - Verify that Day 0 > 50% shows the night label, while Day 1 at 52% does not (since Day 1 threshold is 55%).
- **Emulator/Visual Check**: Verify that a rainy day shows the chance of rain at the top, while a rainy night (following a clear day) shows the chance of rain at the bottom with a font size that shrinks for later days.
