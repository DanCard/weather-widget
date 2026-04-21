# Fix Samsung Header Font Scaling

## Background & Motivation
The widget's text elements are currently sized inconsistently across devices. Samsung devices (and potentially other OEMs) sometimes apply the system's "Font Size" accessibility scaling (`scaledDensity`) to elements sized using `sp` in XML or `COMPLEX_UNIT_DIP` via `RemoteViews`, leading to visual discrepancies where the header text is significantly smaller than the explicitly pixel-calculated text in the graphs. To ensure 100% visual consistency across all devices, we need to bypass the system's unit conversions and calculate the exact pixel sizes using the app context's display metrics.

## Scope & Impact
- **Impact:** Fixes the inconsistent font scaling in the widget header between Samsung and Pixel devices. Header text will perfectly match the scale of the bitmap graph text regardless of device manufacturer or user font settings.
- **Scope:** Modifies all view handlers that construct the widget header (`TemperatureViewHandler.kt`, `TemperatureViewBinder.kt`, `DailyViewHandler.kt`, `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`, and `TemperatureTouchTargets.kt`).

## Proposed Solution
1. **Switch to Pixel Calculations:** Replace all `setTextViewTextSize` calls that use `COMPLEX_UNIT_DIP` with `COMPLEX_UNIT_PX`.
2. **Calculate Pixels Manually:** Calculate the exact pixel size using `TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, context.resources.displayMetrics)` or the equivalent `dpToPx` helper function.
3. **Explicitly Size `current_temp_delta`:** The `current_temp_delta` TextView is currently only sized via XML (`14sp`). We will add explicit programmatic sizing for it whenever it is populated.

## Implementation Steps
### Update `setTextViewTextSize` usages
For each of the following view handlers, locate all `setTextViewTextSize` calls:
- `TemperatureViewHandler.kt`
- `TemperatureViewBinder.kt`
- `DailyViewHandler.kt`
- `PrecipViewHandler.kt`
- `CloudCoverViewHandler.kt`
- `TemperatureTouchTargets.kt`

Change the logic from:
```kotlin
views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dpValue)
```
To:
```kotlin
val pxSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, context.resources.displayMetrics)
views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, pxSize)
```

**Targeted IDs:**
- `R.id.current_temp`
- `R.id.precip_probability`
- `R.id.api_source`
- `R.id.text_mode_api_source`
- `R.id.header_date_center`
- `R.id.header_date_right`

### Add sizing for `current_temp_delta`
Wherever `current_temp_delta` is set to `View.VISIBLE`, explicitly set its text size to `14f` DP converted to PX:
```kotlin
val deltaPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, context.resources.displayMetrics)
views.setTextViewTextSize(R.id.current_temp_delta, TypedValue.COMPLEX_UNIT_PX, deltaPx)
```

## Verification
- Verify that no `COMPLEX_UNIT_DIP` or `COMPLEX_UNIT_SP` usages remain for text size inside the view handlers.
- Deploy to an emulator or physical device. Adjust the system "Font Size" in Settings and verify the widget header text size remains stable and matches the graph text scaling.
