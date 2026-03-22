# Fix Header Overlap — Width-Based Threshold

## Context
The hourly temperature graph header overlaps on the Pixel 7 Pro (widget sizeDp=373dp, 6 cols).
The root cause: the current `isNarrow = numColumns <= 3` threshold never fires on the Pixel.

**Device comparison:**
| Device | DPI | Widget widthDp | cols | Stations icon left edge | Left content end | Overlap? |
|---|---|---|---|---|---|---|
| Pixel 7 Pro | 420 | 373dp | 6 | 186 − 57 = **129dp** | ~130dp | **Yes (1dp gap)** |
| Samsung Fold 4 | 485 override | ~597dp | many | 298 − 57 = **241dp** | ~130dp | No |

Column count is a poor proxy — the Pixel at 6 cols has tighter dp space than the Fold at fewer cols.
**`widthDp` is already density-independent and the correct measure.**

## Change: Replace `numColumns <= 3` with `widthDp < 420` in `positionCenterIcons`

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

In `positionCenterIcons`, change:
```kotlin
val useInline = isNarrow && isPrecipVisible
```
…where `isNarrow` is currently passed as `numColumns <= 3`.

Replace the call site (after the precip block, ~line 531):
```kotlin
// Before:
positionCenterIcons(
    views = views,
    isNarrow = numColumns <= 3,
    isPrecipVisible = headerPrecipProbability != null && headerPrecipProbability > 0,
)

// After:
positionCenterIcons(
    views = views,
    isNarrow = dimensions.widthDp < 420,
    isPrecipVisible = headerPrecipProbability != null && headerPrecipProbability > 0,
)
```

`dimensions` is already in scope at the call site (it's a parameter of `updateWidget`).

**Why 420dp:** The stations icon left edge = widthDp/2 − 57. With left content ending at ~130dp
(temp + delta + precip), overlap begins at widthDp ≈ 374dp. 420dp adds ~46dp buffer to account
for wider precip values (e.g., "45%" at full 26sp extends left content by ~30dp more).

### Also: Remove `isNarrow` parameter from `positionCenterIcons`

Fold the width check directly into the function to simplify the signature:
```kotlin
private fun positionCenterIcons(
    views: RemoteViews,
    widthDp: Int,          // renamed from isNarrow: Boolean
    isPrecipVisible: Boolean,
) {
    val useInline = widthDp < 420 && isPrecipVisible
    ...
}
```
Call site: `positionCenterIcons(views, dimensions.widthDp, ...)`

## What is NOT changed
- The font size reduction (`numColumns <= 3` → 22sp) is correctly targeted at genuinely tiny
  widgets and does not need to change — the Pixel's problem is icon positioning, not font size.
- All XML changes from the previous plan (API margin, precip paddingEnd) remain in place.

## Files Modified
1. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
   — `positionCenterIcons` signature and call site only

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Check logcat: `positionCenterIcons: ... useInline=true` should appear for Pixel (widthDp=373)
3. Visually confirm inline icons appear after "1%" on Pixel — no overlap with precip text
4. Check Samsung Fold: `useInline=false` — floating centered icons remain
5. Tap home/history/stations inline icons — verify navigation still works
