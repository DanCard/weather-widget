# 260421 - Progressive Disclosure Header for Narrow Widgets

## Summary
When a widget is narrow (e.g., 2 columns), the header elements (weather icon, current temp, delta, precip probability) can crowd or overlap with the API source label. This change implements dynamic measurement-based progressive disclosure to hide header elements in priority order when space is constrained.

## Problem
Currently, the `current_weather_container` (containing weather icon, current temp, delta, and precip probability) is shown whenever graph mode is active, regardless of horizontal space. On narrow 2-column widgets, the header elements crowd the right-side API source label.

## Solution: Progressive Disclosure Priority
Elements are removed in priority order (first to drop = first to hide):
| Priority | Element | Notes |
|----------|---------|-------|
| 1st (drop first) | Weather icon | 24dp + 2dp margin = 26dp |
| 2nd | Current temp delta | e.g., "+1.2" at 14sp |
| 3rd | Precip probability | e.g., "100%" at 26sp |
| 4th (keep last) | Current temp | Always show if any space available |

Algorithm:
1. Try full header (icon + current_temp + delta + precip)
2. If doesn't fit → drop icon → current_temp + delta + precip
3. If doesn't fit → drop delta → current_temp + precip
4. If doesn't fit → drop precip → current_temp only
5. If current_temp doesn't fit → hide entire `current_weather_container`

## Files to Create

### 1. `app/src/main/java/com/weatherwidget/widget/handlers/HeaderWidthChecker.kt`
Shared utility for measuring header element widths and determining progressive disclosure.

Key method:
```kotlin
enum class HeaderDisclosureLevel {
    FULL,           // icon + current_temp + delta + precip
    NO_ICON,        // current_temp + delta + precip
    NO_ICON_NO_DELTA,   // current_temp + precip
    MINIMAL,        // current_temp only
    NONE            // nothing fits
}

fun resolveHeaderDisclosure(
    context: Context,
    widthDp: Int,
    apiSourceText: String,
    apiTextSizeSp: Float,
    currentTempText: String?,
    deltaText: String?,
    precipText: String?,
    precipTextSizeSp: Float?,
): HeaderDisclosureLevel
```

Implementation:
- Use `Paint.measureText()` to measure actual text widths (existing pattern in `DailyViewHandler.resolveHeaderDatePlacement()`)
- Compute left cluster: icon (24dp+2dp) + current_temp + delta + precip
- Compute right cluster: API source positioned at `width - 32dp - apiContainerWidth`
- Check if `leftClusterRight + 6dp gap <= rightClusterLeft`
- Return the highest disclosure level that fits

## Files to Modify

### 2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
**Lines ~315-322** - Currently shows/hides `current_weather_container` based on `useGraph`:
```kotlin
if (useGraph) {
    views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
    views.setViewVisibility(R.id.current_weather_container, View.VISIBLE)
} else {
    ...
}
```

Change to:
```kotlin
val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
    context = context,
    widthDp = dimensions.widthDp,
    apiSourceText = apiSourceText,
    apiTextSizeSp = apiTextSizeSp(numRows),
    currentTempText = formattedTemp,
    deltaText = if (deltaVisible) deltaText else null,
    precipText = if (isPrecipVisible) "$precipProb%" else null,
    precipTextSizeSp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(precipProb) else null,
)

val showHeader = useGraph && disclosure != HeaderDisclosureLevel.NONE
views.setViewVisibility(R.id.weather_icon, if (disclosure.showsIcon()) View.VISIBLE else View.GONE)
views.setViewVisibility(R.id.current_weather_container, if (showHeader) View.VISIBLE else View.GONE)
views.setViewVisibility(R.id.current_temp_delta, if (disclosure.showsDelta()) View.VISIBLE else View.GONE)
views.setViewVisibility(R.id.precip_probability, if (disclosure.showsPrecip()) View.VISIBLE else View.GONE)
```

### 3. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewBinder.kt`
**Lines ~42-70** - Add container visibility and disclosure logic:
- `weather_icon` visibility based on disclosure level
- `current_temp` visibility (already conditional on `header.currentTemp != null`)
- `current_temp_delta` visibility based on `header.isDeltaVisible && disclosure.showsDelta()`
- `precip_probability` visibility based on `header.isPrecipVisible && disclosure.showsPrecip()`
- `current_weather_container` visibility based on disclosure != NONE

Add call to `HeaderWidthChecker.resolveHeaderDisclosure()` using state data (state.widthDp, state.numRows).

### 4. `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
**Lines ~190-223** - Add container visibility and disclosure logic:
- Same pattern as TemperatureViewBinder
- Note: PrecipViewHandler sets `isPrecipVisible = true` always (shows 0% for confirmation), so precip is always "present" in measurement

### 5. `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
**Lines ~237-265** - Add container visibility and disclosure logic:
- Same pattern as TemperatureViewBinder

### 6. `app/src/test/java/com/weatherwidget/widget/handlers/HeaderWidthCheckerTest.kt`
New JVM unit test for `HeaderWidthChecker`. Test cases:
- Full header fits at 300dp, 400dp, 500dp widths
- Icon drops at narrow widths (2 columns = ~140dp)
- Delta drops before icon when only room for current_temp + precip
- Precip drops after delta when only room for current_temp + delta
- Entire container hides when current_temp doesn't fit
- Edge cases: null values for delta/precip

### 7. `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt`
Update existing tests that assert on `current_weather_container`, `weather_icon`, `current_temp_delta`, `precip_probability` visibility to account for new width-based disclosure.

## Constants Reference (from DailyViewHandler)
```
WEATHER_ICON_WIDTH_DP = 24f
WEATHER_ICON_END_MARGIN_DP = 2f
CURRENT_TEMP_DELTA_TEXT_SIZE_SP = 14f
HEADER_DATE_HORIZONTAL_GAP_DP = 6f
HEADER_DATE_RIGHT_MARGIN_DP = 112f (API source margin)
```

## API Source Label Position
- `layout_gravity = top|end`
- `layout_marginEnd = 32dp`
- Actual right edge at `width - 32dp`
- API container has `paddingStart=8dp, paddingEnd=6dp`
- API left position = `widthPx - 32dp - (14dp + apiTextWidthPx)`

## Gap Requirement
- 6dp horizontal gap between left cluster and right cluster (from `HEADER_DATE_HORIZONTAL_GAP_DP`)