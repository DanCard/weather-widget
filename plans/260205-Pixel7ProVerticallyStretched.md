# Pixel 7 Pro: Vertically Stretched Graph Analysis

**Date:** 2026-02-05
**Device:** Pixel 7 Pro (2A191FDH300PPW), 1080x2340, ~420dpi

## Summary

The daily forecast graph bars appear vertically stretched on the Pixel 7 Pro. The root cause is a mismatch between the rendered bitmap dimensions and the actual ImageView size, combined with `fitXY` scaling that distorts the aspect ratio.

---

## Device Log Evidence

From logcat during widget render:

```
Widget ID 49 (2-row graph widget):
  minWidth=373dp, minHeight=167dp → 6 cols, 2 rows
  Bitmap target: 388dp × 180dp
  Downscaled: 1018×472px → 696×322px (ratio 2.16:1)

Widget ID 50 (1-row widget, still triggers graph mode):
  minWidth=373dp, minHeight=107dp → 6 cols, 1 row
  rawRows = (107+25)/90 = 1.47 ≥ 1.4 threshold → uses graph mode
  Bitmap: 985×228px
```

## Root Cause: Three Contributing Factors

### 1. `fitXY` Scale Type on ImageView (Primary Cause)

**File:** `app/src/main/res/layout/widget_weather.xml:331`

```xml
<ImageView
    android:id="@+id/graph_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:scaleType="fitXY"    <!-- STRETCHES non-uniformly -->
    android:visibility="gone" />
```

The ImageView is `match_parent` × `match_parent` inside a FrameLayout with 8dp padding. The actual view size is determined by the launcher's widget allocation, which may differ from the bitmap dimensions. `fitXY` independently scales width and height to fill the view, **breaking the aspect ratio**.

**Example on Pixel 7 Pro:**
- Bitmap created at: 696×322px (ratio 2.16:1)
- ImageView actual size on screen: ~1026×550px (ratio 1.87:1)
- Height scaled 1.71x but width scaled only 1.47x → **vertical stretch**

### 2. Bitmap Size Doesn't Match Actual Widget Size

**File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt:827-830`

```kotlin
val widthDp = numColumns * CELL_WIDTH_DP - 32   // 6*70-32 = 388dp
val heightDp = numRows * CELL_HEIGHT_DP          // 2*90 = 180dp
val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)
```

The bitmap size is calculated from a grid formula (`cols * 70dp`, `rows * 90dp`) rather than the actual widget dimensions (`minWidth=373dp`, `minHeight=167dp`). This creates a bitmap with a different aspect ratio than the widget's actual space.

**The mismatch:**
- Grid formula: 388dp × 180dp = ratio 2.16:1
- Actual widget: 373dp × 167dp = ratio 2.23:1
- After padding (8dp each side): ~357dp × 151dp = ratio 2.36:1

### 3. Graph Uses All Available Vertical Space (No Capping)

**File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt:91-95`

```kotlin
val graphTop = topPadding                                          // ~22dp
val graphBottom = heightPx - dayLabelHeight - attachedStackHeight   // height - ~38dp
val graphHeight = graphBottom - graphTop                            // all remaining space
```

The graph area expands to fill 100% of remaining vertical space. There's no maximum height cap relative to width. On tall widgets, this produces exaggerated vertical bar lengths.

**Current temperature data:**
- Range: 43°F to 73°F = 30°F
- Graph height: ~62dp (for 122dp effective height)
- Result: ~2dp per degree → bars span most of the widget height

---

## Layout Structure

```
FrameLayout (widget_root, match_parent × match_parent, 8dp padding)
├── ImageButton (nav_left, 20dp wide)
├── ImageButton (nav_right, 20dp wide)
├── LinearLayout (text_container, gone in graph mode)
├── ImageView (graph_view, match_parent × match_parent, fitXY) ← THE GRAPH
├── LinearLayout (graph_day_zones, touch targets)
├── FrameLayout (nav_left_zone, 32dp wide)
├── FrameLayout (nav_right_zone, 32dp wide)
├── FrameLayout (current_temp_zone)
├── LinearLayout (current_weather_container, top-left overlay)
└── FrameLayout (api_source_container, top-right overlay)
```

The graph ImageView sits behind the overlaid current temp, API source, and nav arrows. It fills the entire widget minus 8dp padding on each side plus 4dp margin start/end.

---

## Fix Strategy

### Fix 1: Use Actual Widget Dimensions for Bitmap Size

In `WeatherWidgetProvider.kt`, use `minWidth`/`minHeight` from `WidgetDimensions` instead of the grid formula:

```kotlin
// Instead of:
val widthDp = numColumns * CELL_WIDTH_DP - 32
val heightDp = numRows * CELL_HEIGHT_DP

// Use actual widget dimensions (minus padding/margins):
val widthDp = dimensions.minWidth - 16 - 8   // minus layout margins (4dp+4dp) and padding (8dp+8dp... wait need to check)
val heightDp = dimensions.minHeight - 16      // minus padding
```

This ensures the bitmap aspect ratio matches the ImageView's actual aspect ratio, making `fitXY` a no-op (no distortion).

### Fix 2: Change `fitXY` to `fitCenter` (Safety Net)

In `widget_weather.xml:331`, change:
```xml
android:scaleType="fitCenter"
```

This preserves aspect ratio by scaling uniformly with letterboxing. However, this alone could leave empty space if the bitmap ratio doesn't match the view ratio, so Fix 1 is the real solution.

### Fix 3 (Optional): Cap Graph Height Relative to Width

In `DailyForecastGraphRenderer.kt`, add a maximum aspect ratio for the graph area:

```kotlin
val maxGraphHeight = (widthPx * 0.6f)  // cap at 60% of width
val graphHeight = (graphBottom - graphTop).coerceAtMost(maxGraphHeight)
// Center vertically if capped
val graphTop = if (uncappedHeight > maxGraphHeight) {
    topPadding + (uncappedHeight - maxGraphHeight) / 2
} else topPadding
```

---

## Key Files to Modify

| File | Change |
|------|--------|
| `app/src/main/res/layout/widget_weather.xml:331` | Change `fitXY` → `fitCenter` |
| `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt:827-830` | Use actual widget dimensions for bitmap size |
| `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt:91-95` | Optional: cap graph height relative to width |
| `app/src/main/java/com/weatherwidget/widget/HourlyGraphRenderer.kt` | Same fixes as DailyForecastGraphRenderer |

## Verification

1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Check on Pixel 7 Pro that bars are no longer vertically stretched
3. Also verify on emulator (`Medium_Phone_API_36`) that layout still looks correct
4. Test widget at different sizes (resize to 1x3, 2x3, 4+ cols)
5. Check logcat for bitmap dimensions matching widget dimensions
