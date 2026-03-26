# Fix: Daily Nav Column Count Stability

## Context

When navigating backward (left arrow) in the daily forecast graph view, the number of visible columns changes (e.g., 8 → 9). The widget's physical size hasn't changed — the column count should remain constant regardless of navigation offset. The root cause is that `DayData.columnIndex` is assigned based on the list insertion order (`days.size`) rather than the day's fixed position within the `numColumns` grid.

## The Bug

In `DailyViewLogic.prepareGraphDays()` (line 350):
```kotlin
columnIndex = days.size,  // BUG: sequential list index, not grid position
```

When a day in the date window has no data, `return@forEachIndexed` skips it (lines 224/227/230). This means `days.size` diverges from `index` — a day at grid position 5 might get `columnIndex = 4` if one earlier day was skipped. The renderer then places bars in wrong slots, and the visual column count appears to change.

## Fix

### 1. `DailyViewLogic.kt` — line 350
Change `columnIndex = days.size` to `columnIndex = index`

This assigns each day its position within the `dayOffsets` array (its true grid slot), so skipped days leave empty columns rather than collapsing the grid.

**Files:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`

### 2. Update existing tests

- `DailyViewGraphClickAlignmentTest.kt` — verify that `columnIndex` values match grid positions, not list positions, especially when days are skipped
- `DailyGraphTouchZoneAlignmentInstrumentedTest.kt` — verify zone count stays at `numColumns` across navigation offsets

**Files:**
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewGraphClickAlignmentTest.kt`
- `app/src/androidTest/java/com/weatherwidget/widget/handlers/DailyGraphTouchZoneAlignmentInstrumentedTest.kt`

## Why This Works

The downstream code already handles this correctly:
- `DailyForecastGraphRenderer.renderGraph()` (line 124): uses `numColumns` for layout width, not `days.size`
- `renderGraph()` (line 132): positions each bar using `day.columnIndex` — so correct columnIndex = correct placement
- `setupGraphDayClickHandlers()` (line 860): sets visibility for `numColumns` zones, attaches clicks using `day.columnIndex`

The only broken link is the `columnIndex` assignment itself.

## Verification

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewGraphClickAlignmentTest"`
2. Deploy to emulator, navigate left/right in daily view — column count should stay constant
3. Run instrumented tests: `./scripts/run-emulator-tests.sh -c com.weatherwidget.widget.handlers.DailyGraphTouchZoneAlignmentInstrumentedTest`
