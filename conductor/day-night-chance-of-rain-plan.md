# Plan: Shift Night Rain Labels to Inter-Column Gaps

This plan addresses the request to move nighttime precipitation chance labels in the daily forecast view to the space between day bars, making them more logically associated with the transition between days.

## Objective
- Allow both day and night rain labels to be displayed for the same day if both are significant.
- Shift the night rain label horizontally so it sits in the gap between the current day and the next day.
- Ensure the label is only shifted when there is sufficient room and it won't overflow the widget boundaries.

## Key Files & Context
- `DailyViewLogic.kt`: Populates the `DayData` and `RainData` for the renderer.
- `DailyForecastGraphRenderer.kt`: Handles the actual drawing of bars and labels.
- `RainData`: Data class holding both `dailyRainLabelText` and `nightRainLabelText`.

## Implementation Steps

### 1. Update Data Population
In `DailyViewLogic.kt`, modify `buildNightRainLabel` to remove the suppression check that skips the night label if a day label already exists.

### 2. Update Renderer Logic
In `DailyForecastGraphRenderer.kt`, modify `drawNightRainLabel` to:
- Calculate a potential `shiftedCenterX = centerX + layout.dayWidth / 2f`.
- Implement a multi-step fitting strategy:
    1. **Standard Shift**: Try placing at `shiftedCenterX` with standard font size.
    2. **Scaled Shift**: If it overflows the widget boundary, try a smaller font scale (down to `MIN_RAIN_FONT_SCALE`).
    3. **Allow Slight Overlap**: Relax the `maxTextWidth` constraint for nighttime labels to allow them to sit between columns even if they slightly encroach on the horizontal padding of the icons.
    4. **Fallback**: If still impossible, fallback to `NIGHT_CENTERED` or skip if extremely crowded.
- Use this new horizontal position and potentially adjusted font size for drawing the text.

### 3. Verification & Testing
- Use a mock location with both day and night rain (e.g., "50% chance of rain during the day, 90% at night").
- Verify that both labels appear: the day label above the bar and the night label below and to the right of the bar.
- Test with different column counts (3, 5, 7) to ensure "room" logic works correctly on narrow vs. wide columns.
- Check 1-column (1x1) widget behavior.

## Proposed Code Changes

### DailyViewLogic.kt
```kotlin
// In buildNightRainLabel
- if (dailyRainLabelText != null) {
-     Log.d(TAG, "buildNightRainLabel skipping because day label exists: date=$date dayLabel=$dailyRainLabelText")
-     return null
- }
```

### DailyForecastGraphRenderer.kt
```kotlin
// In drawNightRainLabel
val textWidth = localRainPaint.measureText(rainText)
val halfWidth = textWidth / 2f
val shiftedCenterX = centerX + layout.dayWidth / 2f

val (finalCenterX, placementType) = when {
    shiftedCenterX + halfWidth <= layout.widthPx -> {
        shiftedCenterX to "NIGHT_SHIFTED_RIGHT"
    }
    else -> {
        centerX to "NIGHT_CENTERED"
    }
}

// ... use finalCenterX for drawing ...
```

## Alignment Check
- The night label remains vertically positioned below the low temperature stack, avoiding collisions with the day label (above high) or the bars themselves.
- Shifting to the right boundary correctly places it "between" the bars as requested.
