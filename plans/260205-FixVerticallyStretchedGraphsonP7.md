# Fix: Vertically Stretched Graphs on Pixel 7 Pro

## Problem
Both the daily forecast graph and hourly graph appeared vertically stretched on the Pixel 7 Pro.

## Root Cause
`getWidgetSize()` in `WeatherWidgetProvider.kt` only read `OPTION_APPWIDGET_MIN_WIDTH` and `OPTION_APPWIDGET_MIN_HEIGHT`. Android provides both min and max widget dimensions, and the actual rendered size depends on orientation:

- **Portrait**: actual size = `minWidth × maxHeight`
- **Landscape**: actual size = `maxWidth × minHeight`

By using `minWidth × minHeight`, the bitmap height was too small for portrait mode. When the ImageView's `fitXY` scaleType stretched this shorter bitmap to fill the taller actual view, the graph appeared vertically stretched. The Pixel 7 Pro's tall, narrow screen (1440×3120, 19.5:9) made the delta between `minHeight` and `maxHeight` especially large, making the distortion very visible.

Secondary factor: `getOptimalBitmapSize()` used `.toInt()` truncation when downscaling, which introduced minor aspect ratio drift.

## Changes Applied

### 1. Orientation-aware widget dimensions
**File:** `app/.../WeatherWidgetProvider.kt` (lines 643-666)

- Now reads all four dimension options: `MIN_WIDTH`, `MIN_HEIGHT`, `MAX_WIDTH`, `MAX_HEIGHT`
- Selects the correct pair based on device orientation:
  - Portrait: `minWidth × maxHeight`
  - Landscape: `maxWidth × minHeight`
- `WidgetDimensions` data class fields renamed from `minWidth`/`minHeight` → `widthDp`/`heightDp`
- Enhanced logging shows all four raw values, orientation, selected dimensions, and computed cols/rows
- Fallback: `maxWidth` defaults to `minWidth`, `maxHeight` defaults to `minHeight` (safe for launchers that don't report max dimensions)

### 2. `fitXY` → `fitCenter`
**File:** `app/src/main/res/layout/widget_weather.xml` (line 331)

```xml
- android:scaleType="fitXY"
+ android:scaleType="fitCenter"
```

Safety net: with correct bitmap dimensions from fix #1, the aspect ratio should match closely. But if there's ever a slight mismatch, `fitCenter` scales uniformly (no distortion) rather than stretching independently on each axis.

### 3. Rounding fix in `getOptimalBitmapSize()`
**File:** `app/.../WeatherWidgetProvider.kt` (lines 676-677)

```kotlin
- val newWidth = (rawWidth * scale).toInt()
- val newHeight = (rawHeight * scale).toInt()
+ val newWidth = (rawWidth * scale).roundToInt()
+ val newHeight = (rawHeight * scale).roundToInt()
```

`roundToInt()` was already imported (line 33). Prevents 1-pixel aspect ratio drift from truncation during downscaling.

## Files Modified
| File | Lines | Change |
|------|-------|--------|
| `WeatherWidgetProvider.kt` | 7 | Added `import android.content.res.Configuration` |
| `WeatherWidgetProvider.kt` | 643-666 | Orientation-aware min/max dimension selection |
| `WeatherWidgetProvider.kt` | 676-677 | `.toInt()` → `.roundToInt()` |
| `widget_weather.xml` | 331 | `fitXY` → `fitCenter` |

## What Stayed the Same
- Graph renderers (`DailyForecastGraphRenderer.kt`, `HourlyGraphRenderer.kt`) were NOT changed — they continue to use the full available vertical space
- `MAX_BITMAP_PIXELS` limit and downscaling logic unchanged (just better rounding)
- All widget sizing thresholds unchanged (graph vs text mode, etc.)

## Why This Only Manifests on Certain Devices
The Pixel 7 Pro has a tall, narrow screen (19.5:9 ratio). In portrait mode, a 2-row widget gets a large `maxHeight` but the same `minHeight` as on a less-extreme device. The delta between `minHeight` and `maxHeight` is proportionally larger on tall screens, making the vertical stretch more pronounced. Shorter/wider devices show the same issue but less noticeably.

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Run tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — all pass
3. Check logcat for `getWidgetSize` entries showing `minW`, `minH`, `maxW`, `maxH`, `isPortrait`
4. Test on Pixel 7 Pro — daily and hourly graphs should no longer appear stretched
5. Verify at multiple widget sizes (2-row, 3-row)
6. Test on emulator for no regression
