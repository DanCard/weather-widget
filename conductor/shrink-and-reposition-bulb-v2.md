# Plan - Shrink and Reposition the "Today" Temperature Bulb (Revised)

Based on log analysis from the emulator, the "Today" temperature bulb has a radius of **6.6px**, which is significantly larger than the minimum bar height of **2.6px** (1dp at density 2.625). This causes the bulb to overlap and obscure a significant portion of the temperature-driven stem, making the entire assembly look disproportionately tall on some layouts.

## Objective
Make the "Today" temperature indicator more compact and responsive to narrow temperature ranges by:
1.  **Reducing the Bulb Size**: Shrinking the bulb's radius multiplier.
2.  **Adjusting Vertical Alignment**: Shifting the bulb down so its top is closer to the end of the stem, minimizing overlap.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains the rendering logic for the triple-bar Today column.

## Implementation Steps

### 1. Reduce Bulb Radius Multiplier
Update the `bulbRadius` calculation to use a smaller multiplier.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Change:** Update line 164 from `val bulbRadius = tripleBarWidth * 1.8f` to `val bulbRadius = tripleBarWidth * 1.3f`.

### 2. Reposition the Bulb (Vertical Shift)
Adjust the `drawCircle` calls to shift the center of the bulb down by **60% of its radius**. This makes the bulb appear "attached" to the end of the stem rather than centered *over* it, while maintaining a clean connection.

- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Changes:** Update all three `canvas.drawCircle` calls for the yellow bulb:
    - Line 352: `canvas.drawCircle(centerX, effectiveLowY + (bulbRadius * 0.6f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 391: `canvas.drawCircle(centerX, (highY!! + minBarHeight) + (bulbRadius * 0.6f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 424: `canvas.drawCircle(centerX, lowY!! + (bulbRadius * 0.6f), bulbRadius, todayTripleYellowBulbPaint)`

## Verification & Testing

### Manual Verification
1.  **Visual Check**: Verify that the Today bulb appears smaller and is positioned at the very bottom of the center bar.
2.  **Narrow Range**: Check a day with a 1° or 0° temperature range. The total height (stem + bulb) should be significantly more compact.
3.  **Logs**: Verify the new `bulbRadius` in `adb logcat | grep DailyGraphRenderer`. It should now be around **4.8px** (at density 2.625).

### Regression Testing
1.  **Rendering Quality**: Ensure the circle still appears centered horizontally on the stem and that the connection looks smooth.
