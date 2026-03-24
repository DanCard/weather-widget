# Plan - Shrink and Reposition the "Today" Temperature Bulb (Diagnosis-Driven)

Analysis of emulator logs confirms that the "Today" bar's height is primarily driven by the **19°F temperature range** (High 64°F, Low 45°F), which translates to **183px** on a 385px tall graph. However, the circular "bulb" at the bottom has a fixed diameter of **13.2px** (Radius 6.6px) and is centered on the stem's end. This fixed overhead makes the indicator look disproportionately tall, especially in narrower temperature ranges where the bulb dominates the stem.

## Objective
Make the "Today" temperature indicator more compact by reducing the fixed visual overhead of the bulb:
1.  **Shrink the Bulb**: Reduce the radius multiplier.
2.  **Adjust Alignment**: Shift the bulb down so it doesn't overlap the stem as much.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Contains the rendering logic for the triple-bar Today column.

## Implementation Steps

### 1. Reduce Bulb Radius Multiplier
Shrink the circle so it is less dominant.
- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Change:** Update line 164 from `val bulbRadius = tripleBarWidth * 1.8f` to `val bulbRadius = tripleBarWidth * 1.2f`.
- **Result (Emulator):** Radius reduces from **6.6px** to **~4.4px** (Diameter ~9px).

### 2. Reposition the Bulb (Vertical Shift)
Shift the bulb's center down by 50% of its radius. This places the center of the circle at the bottom of the stem, but with less overlap than the previous "fully centered" approach.
- **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- **Changes:** Update all three `canvas.drawCircle` calls for the yellow bulb:
    - Line 352: `canvas.drawCircle(centerX, effectiveLowY + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 391: `canvas.drawCircle(centerX, (highY!! + minBarHeight) + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`
    - Line 424: `canvas.drawCircle(centerX, lowY!! + (bulbRadius * 0.5f), bulbRadius, todayTripleYellowBulbPaint)`

## Verification & Testing

### Manual Verification
1.  **Visual Check**: Verify that the Today bulb appears smaller and less "heavy" at the bottom.
2.  **Narrow Range**: Simulate or wait for a day with a small temperature range. The total height should be more compact.
3.  **Logs**: Check `adb logcat | grep DailyGraphRenderer` to confirm `bulbRadius` is now ~4.4px on the emulator.
