# Plan - Shrink and Reposition the "Today" Temperature Bulb

The "Today" column in the daily forecast view uses a "triple bar" with a circular "bulb" at the bottom of the center bar. Even with a 1dp minimum bar height, the bulb's diameter (~5dp) makes the entire assembly look disproportionately tall when the temperature range is narrow. This occurs because the bulb is centered on the bottom of the temperature-driven "stem," causing it to overlap and obscure a significant portion of the stem.

## Objective
Make the "Today" temperature indicator more compact and responsive to narrow temperature ranges by:
1.  **Reducing the Bulb Size**: Shrinking the bulb's radius.
2.  **Adjusting Vertical Alignment**: Shifting the bulb down so it doesn't overlap the stem as much.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains the rendering logic for the triple-bar Today column.

## Implementation Steps

### 1. Reduce Bulb Radius Multiplier
Update the `bulbRadius` calculation to use a smaller multiplier.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Change:** Update line 164 from `val bulbRadius = tripleBarWidth * 1.8f` to `val bulbRadius = tripleBarWidth * 1.2f`.

### 2. Reposition the Bulb (Vertical Shift)
Adjust the `drawCircle` calls to shift the center of the bulb down by half its radius. This makes the bulb appear "attached" to the end of the stem rather than centered *over* it.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Changes:** Update all three `canvas.drawCircle` calls to use `effectiveLowY + (bulbRadius * 0.5f)` or the appropriate `lowY` variable:
    - Line 346: `canvas.drawCircle(centerX, effectiveLowY + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 384: `canvas.drawCircle(centerX, (highY!! + minBarHeight) + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 417: `canvas.drawCircle(centerX, lowY!! + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`

## Verification & Testing

### Manual Verification
1.  **Visual Check**: Verify that the Today bulb appears smaller and is positioned at the bottom of the center bar.
2.  **Narrow Range**: Check a day with a 1° or 0° temperature range. The total height of the stem and bulb should be noticeably shorter than before.
3.  **Triple Line Alignment**: Ensure the snapshot (left) and forecast (right) lines of the triple-bar still align correctly with the stem of the center bar.

### Regression Testing
1.  **Rendering Quality**: Verify that the bulb still looks like a clean, filled circle and that it remains centered horizontally on the stem.
