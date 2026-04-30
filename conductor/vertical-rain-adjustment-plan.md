# Plan: Vertical Adjustment for Night Rain Labels

This plan fixes the "bottom overflow" issue for night rain labels by moving them vertically into the gap between bars when they are shifted horizontally.

## Objective
- Resolve the issue where night rain labels are skipped due to "bottom overflow" in tight widget layouts.
- Implement the "between horizontal bars" placement by vertically centering the night rain label relative to the forecast bars when it sits in the inter-column gap.

## Key Files & Context
- `DailyForecastGraphRenderer.kt`: Contains `drawNightRainLabel` and the vertical anchoring logic.

## Implementation Steps

### 1. Calculate Bar Midpoint
In `drawNightRainLabel`, calculate the vertical midpoint of the temperature bar area for the current day.
```kotlin
val highY = day.high?.let { layout.tempToY(it) } ?: layout.graphTop
val lowTemp = resolveBottomStackLow(day) ?: day.low ?: 0f
val lowY = layout.tempToY(lowTemp)
val barMidY = (highY + lowY) / 2f
```

### 2. Adjust Vertical Anchoring
Modify the `baseline` calculation in `drawNightRainLabel` to use the bar midpoint if the label is shifted to the right.
- If `placementType` is `NIGHT_SHIFTED_RIGHT` or `NIGHT_SHIFTED_SCALED`:
    - Set the vertical center of the text to `barMidY`.
    - `baseline = barMidY - (metrics.ascent + metrics.descent) / 2f`.
- Otherwise (centered), keep the existing "below low stack" logic but potentially relax the overflow check if it's close.

### 3. Verification & Testing
- Deploy to emulator and check logs for "nightRainLabel" status.
- Verify that labels now appear in the gap between columns, vertically centered relative to the bars.
- Ensure 1-column widgets still behave reasonably (they will likely use the centered fallback).

## Alignment Check
- This placement directly addresses "putting rain chance in between horizontal bars" by placing the text in the horizontal AND vertical center of the gap between the bars.
- It avoids the crowded bottom area of the widget, which is the primary cause of the "bottom overflow" skips.
