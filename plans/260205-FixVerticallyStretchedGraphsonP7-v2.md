# Fix: Vertically Stretched Graphs on Pixel 7 Pro

## Problem
Both the daily forecast graph and hourly graph appear vertically stretched on the Pixel 7 Pro.

## Root Cause

The code only reads `OPTION_APPWIDGET_MIN_WIDTH` and `OPTION_APPWIDGET_MIN_HEIGHT` from the widget options (line 644-645 of `WeatherWidgetProvider.kt`). It never reads `MAX_WIDTH`/`MAX_HEIGHT`.

Android provides both min and max widget dimensions. The actual rendered widget size depends on orientation:
- **Portrait**: actual size ≈ `minWidth` × `maxHeight`
- **Landscape**: actual size ≈ `maxWidth` × `minHeight`

By using `minWidth × minHeight`, the bitmap height is too small for portrait mode. When the ImageView's `fitXY` scaleType stretches this shorter bitmap to fill the taller actual view, the graph appears vertically stretched.

Secondary factor: `getOptimalBitmapSize()` at lines 666-667 uses `.toInt()` truncation when downscaling, which can introduce minor aspect ratio drift.

## Changes

### 1. Use orientation-aware widget dimensions in `getWidgetSize()`
**File:** `app/.../WeatherWidgetProvider.kt` (lines 642-654)

Read all four dimension options and select based on current orientation:

```kotlin
private fun getWidgetSize(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): WidgetDimensions {
    val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

    val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val width = if (isPortrait) minWidth else maxWidth
    val height = if (isPortrait) maxHeight else minHeight

    val cols = ((width + 15).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
    val rows = ((height + 25).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

    Log.d(TAG, "getWidgetSize: widgetId=$appWidgetId, minW=$minWidth, minH=$minHeight, maxW=$maxWidth, maxH=$maxHeight, isPortrait=$isPortrait -> using ${width}x${height}, cols=$cols, rows=$rows")
    return WidgetDimensions(cols, rows, width, height)
}
```

Fallback: `maxWidth` defaults to `minWidth` and `maxHeight` defaults to `minHeight` if the launcher doesn't report max dimensions.

### 2. Change `fitXY` to `fitCenter` (safety net)
**File:** `app/src/main/res/layout/widget_weather.xml` (line 331)

```xml
- android:scaleType="fitXY"
+ android:scaleType="fitCenter"
```

With correct dimensions from fix #1, the bitmap aspect ratio should match the ImageView closely, so `fitCenter` fills all space with no visible letterboxing. But if there's ever a slight mismatch, `fitCenter` scales uniformly (no distortion) rather than stretching independently on each axis.

### 3. Fix rounding in `getOptimalBitmapSize()`
**File:** `app/.../WeatherWidgetProvider.kt` (lines 666-667)

```kotlin
- val newWidth = (rawWidth * scale).toInt()
- val newHeight = (rawHeight * scale).toInt()
+ val newWidth = (rawWidth * scale).roundToInt()
+ val newHeight = (rawHeight * scale).roundToInt()
```

`roundToInt()` is already imported (line 33). This minimizes aspect ratio drift from truncation during downscaling.

## Files Modified
| File | Lines | Change |
|------|-------|--------|
| `app/.../WeatherWidgetProvider.kt` | 642-654 | Use orientation-aware min/max dimensions |
| `app/.../WeatherWidgetProvider.kt` | 666-667 | `.toInt()` → `.roundToInt()` |
| `app/src/main/res/layout/widget_weather.xml` | 331 | `fitXY` → `fitCenter` |

## What Stays the Same
- Graph renderers (`DailyForecastGraphRenderer.kt`, `HourlyGraphRenderer.kt`) are NOT changed — they continue to use the full available vertical space
- `MAX_BITMAP_PIXELS` limit and downscaling logic unchanged (just better rounding)
- All widget sizing thresholds unchanged (graph vs text mode, etc.)

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Run existing tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
3. Test on Pixel 7 Pro — daily and hourly graphs should no longer appear stretched
4. Check logcat for new dimension logging (`minW`, `minH`, `maxW`, `maxH`, `isPortrait`)
5. Verify at multiple widget sizes (2-row, 3-row)
6. Test on emulator for no regression
