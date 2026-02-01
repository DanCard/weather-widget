# Plan: Hourly Temperature View with Navigation

## Summary
Add an hourly temperature view that users can access by tapping the current temperature. The view shows a 24-hour window (6 hours past + 18 hours future) with scroll buttons to navigate through time. The display adapts to widget size: graph mode for 2+ rows, text mode for 1 row.

## User Requirements
✓ **Time range**: 6 hours past + 18 hours future (24 hours total)
✓ **Navigation**: Scroll buttons to advance/go backwards (±6 hour jumps)
✓ **Display mode**: Expanded widget view (toggle between daily and hourly)
✓ **Click trigger**: Tap current temperature to switch modes
✓ **Responsive design**: More info with larger sizes, text-only for 1 row
✓ **Content**: Current time indicator, hour labels, temperature curve

## Architecture Overview

### State Management
- Add `ViewMode` enum: `DAILY` (default) | `HOURLY`
- Track per-widget: `viewMode`, `hourlyTimeOffset` (hours from now, -6 to +18)
- Reuse existing navigation pattern (left/right arrows change behavior based on mode)

### Data Flow
```
User taps current temp
    ↓
ACTION_TOGGLE_VIEW broadcast
    ↓
WidgetStateManager.toggleViewMode() → HOURLY
    ↓
Query HourlyForecastDao for 24-hour window
    ↓
Render HourlyGraphRenderer (graph) or hourly text mode
    ↓
Update RemoteViews with hourly display
```

### Navigation Behavior
**Daily Mode (existing):**
- Left/Right arrows: Navigate ±1 day
- Range: 30 days past to 14 days future

**Hourly Mode (new):**
- Left/Right arrows: Navigate ±6 hours
- Range: 6 hours past to 18 hours future (centered on offset)
- Offset bounds: -6 to +18 hours from current time

## Implementation Steps

### Phase 1: State Management (WidgetStateManager.kt)

**File**: `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`

1. Add `ViewMode` enum after line 20:
```kotlin
enum class ViewMode {
    DAILY,    // Default: shows daily forecast bars
    HOURLY    // Alternative: shows hourly temperature curve
}
```

2. Add constants after line 31:
```kotlin
private const val KEY_VIEW_MODE_PREFIX = "widget_view_mode_"
private const val KEY_HOURLY_OFFSET_PREFIX = "widget_hourly_offset_"
const val MIN_HOURLY_OFFSET = -6   // 6 hours back
const val MAX_HOURLY_OFFSET = 18   // 18 hours forward
const val HOURLY_NAV_JUMP = 6      // Navigate in 6-hour chunks
```

3. Add view mode methods after line 77:
```kotlin
fun getViewMode(widgetId: Int): ViewMode
fun setViewMode(widgetId: Int, mode: ViewMode)
fun toggleViewMode(widgetId: Int): ViewMode  // Switches DAILY ↔ HOURLY
fun getHourlyOffset(widgetId: Int): Int
fun setHourlyOffset(widgetId: Int, offset: Int)
fun navigateHourlyLeft(widgetId: Int): Int   // Returns new offset
fun navigateHourlyRight(widgetId: Int): Int  // Returns new offset
fun canNavigateHourlyLeft(widgetId: Int): Boolean
fun canNavigateHourlyRight(widgetId: Int): Boolean
```

4. Update `clearWidgetState()` to remove hourly keys

**Testing**: Unit tests for view mode toggling, hourly offset bounds

---

### Phase 2: Hourly Graph Renderer (NEW FILE)

**File**: `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/HourlyGraphRenderer.kt`

Create new object similar to `TemperatureGraphRenderer`:

**Data Structure**:
```kotlin
data class HourData(
    val dateTime: LocalDateTime,
    val temperature: Float,
    val label: String,           // "12a", "1p", "2p"
    val isCurrentHour: Boolean = false,
    val showLabel: Boolean = true  // Only at intervals
)
```

**Rendering Method**:
```kotlin
fun renderGraph(
    context: Context,
    hours: List<HourData>,
    widthPx: Int,
    heightPx: Int,
    currentTime: LocalDateTime
): Bitmap
```

**Key Features**:
- Temperature curve using `Path` with smooth bezier curves
- Current time indicator: vertical dashed line
- Hour labels at bottom (every 3 hours for small, every hour for large)
- Temperature labels at curve peaks/valleys
- Height-based text scaling (reuse TemperatureGraphRenderer pattern)
- Width-based spacing (24 hours visible)

**Reuse from TemperatureGraphRenderer**:
- Paint setup patterns
- Scaling logic (`widthScaleFactor`, `heightScaleFactor`)
- Text rendering with background rectangles
- dpToPx conversion

**Testing**: Bitmap generation with various data sets, visual regression tests

---

### Phase 3: Widget Provider Updates (WeatherWidgetProvider.kt)

**File**: `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

#### 3.1 Add Action Constant (after line 347)
```kotlin
const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
```

#### 3.2 Update onReceive() (add case at line 166)
```kotlin
ACTION_TOGGLE_VIEW -> {
    val appWidgetId = intent.getIntExtra(...)
    val pendingResult = goAsync()
    launch { handleToggleViewDirect(context, appWidgetId) }
}
```

#### 3.3 Modify Navigation to be Mode-Aware (lines 170-226)
```kotlin
private suspend fun handleNavigationDirect(context: Context, appWidgetId: Int, isLeft: Boolean) {
    val viewMode = stateManager.getViewMode(appWidgetId)
    if (viewMode == ViewMode.HOURLY) {
        handleHourlyNavigationDirect(context, appWidgetId, isLeft)
    } else {
        // Existing daily navigation logic
    }
}
```

#### 3.4 Add Hourly Navigation Handler
```kotlin
private suspend fun handleHourlyNavigationDirect(
    context: Context,
    appWidgetId: Int,
    isLeft: Boolean
) {
    // 1. Update hourly offset via WidgetStateManager
    // 2. Calculate time window (offset ±6 to +18)
    // 3. Query HourlyForecastDao for window
    // 4. Call updateWidgetWithHourlyData()
}
```

#### 3.5 Add View Toggle Handler
```kotlin
private suspend fun handleToggleViewDirect(
    context: Context,
    appWidgetId: Int
) {
    val newMode = stateManager.toggleViewMode(appWidgetId)

    if (newMode == ViewMode.HOURLY) {
        // Fetch 24-hour window, render hourly view
    } else {
        // Fetch daily data, render daily view (existing logic)
    }
}
```

#### 3.6 Add Hourly View Renderer
```kotlin
private fun updateWidgetWithHourlyData(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime
) {
    val (numColumns, numRows) = getWidgetSize(...)
    val views = RemoteViews(...)

    // Setup navigation (arrows change to hourly mode)
    setupNavigationButtons(context, views, appWidgetId, stateManager)

    // Setup current temp click (toggle back to daily)
    setupCurrentTempToggle(context, views, appWidgetId)

    // Show API source, current temp (same as daily)

    if (numRows >= 2) {
        // Graph mode: render HourlyGraphRenderer
        val hours = buildHourDataList(hourlyForecasts, centerTime, numColumns)
        val bitmap = HourlyGraphRenderer.renderGraph(...)
        views.setImageViewBitmap(R.id.graph_view, bitmap)
    } else {
        // Text mode: show "Now: 72° | +3h: 68° | +6h: 65°"
        updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns)
    }
}
```

#### 3.7 Add Helper Methods
```kotlin
// Build HourData list from hourly forecasts (24 hours)
private fun buildHourDataList(
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime,
    numColumns: Int
): List<HourlyGraphRenderer.HourData>

// Format hour as "12a", "1p", "2p"
private fun formatHourLabel(time: LocalDateTime): String

// Populate text mode containers with hourly data
private fun updateHourlyTextMode(
    views: RemoteViews,
    hourlyForecasts: List<HourlyForecastEntity>,
    centerTime: LocalDateTime,
    numColumns: Int
)

// Setup current temp to toggle view mode
private fun setupCurrentTempToggle(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int
)
```

#### 3.8 Update Existing Methods

**setupNavigationButtons()** (line 509):
- Check `viewMode` to determine navigation bounds
- Use `canNavigateHourlyLeft/Right()` for hourly mode
- Use `canNavigateLeft/Right()` for daily mode

**updateWidgetWithData()** (line 387):
- Add view mode check at entry
- If `viewMode == HOURLY`, fetch extended hourly data and delegate to `updateWidgetWithHourlyData()`
- Otherwise continue with existing daily view logic

**Testing**: Integration tests for mode switching, navigation, rendering

---

### Phase 4: Data Fetching (WeatherWidgetWorker.kt)

**File**: `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`

**Change**: Extend hourly forecast fetch range from ±3 hours to ±24 hours

**Location**: `fetchHourlyForecasts()` method (lines 104-119)

**Before**:
```kotlin
val startTime = now.minusHours(3).format(...)
val endTime = now.plusHours(3).format(...)
```

**After**:
```kotlin
val startTime = now.minusHours(24).format(...)
val endTime = now.plusHours(24).format(...)
```

**Rationale**: Ensures sufficient hourly data for hourly view navigation (6 past + 18 future = 24 hours)

**Testing**: Verify hourly data availability after fetch

---

## UI/UX Flow

### View Mode Toggle
```
DAILY VIEW (default)
┌────────────────────────────┐
│ 72°                    NWS │  ← Tap current temp
│ ← [Yesterday|Today|Tom] →  │
│ [Temperature bars/text]    │
└────────────────────────────┘
            ↓ Toggle
HOURLY VIEW
┌────────────────────────────┐
│ 72.5°                Meteo │  ← Tap again to return
│ ← [12a 1a 2a ... 11p] →    │
│ [Temperature curve + NOW]  │
└────────────────────────────┘
```

### Navigation Behavior

**Daily Mode**:
- Left arrow: Previous day (dateOffset - 1)
- Right arrow: Next day (dateOffset + 1)
- Current temp tap: Switch to HOURLY mode

**Hourly Mode**:
- Left arrow: 6 hours earlier (hourlyOffset - 6)
- Right arrow: 6 hours later (hourlyOffset + 6)
- Current temp tap: Switch to DAILY mode

### State Persistence
- Each widget remembers its `viewMode` independently
- Each widget remembers its `hourlyOffset` independently
- Switching to HOURLY resets offset to 0 (current time centered)
- State persists across app restarts (SharedPreferences)

---

## Size-Responsive Display

### 1 Row (Text Mode)
```
┌────────────────────────────────────┐
│ Now: 72° | +3h: 68° | +6h: 65°    │
└────────────────────────────────────┘
```
- Reuse existing day containers with new labels
- Show time points: "Now", "+3h", "+6h", "+12h", etc.
- Number of points scales with widget columns

### 2+ Rows (Graph Mode)
```
┌────────────────────────────────────┐
│         [Temperature curve]        │
│            |← NOW                  │
│  12a  3a  6a  9a  12p  3p  6p  9p  │
└────────────────────────────────────┘
```
- Smooth bezier curve connecting hourly temperatures
- Vertical dashed line for current time
- Hour labels at bottom (density based on width)
- Temperature labels at peaks/valleys

---

## Critical Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `WidgetStateManager.kt` | Modify | Add ViewMode enum, hourly offset tracking |
| `HourlyGraphRenderer.kt` | Create | Render hourly temperature curve + current time indicator |
| `WeatherWidgetProvider.kt` | Modify | Toggle logic, hourly navigation, rendering coordination |
| `WeatherWidgetWorker.kt` | Modify | Extend hourly data fetch range to ±24 hours |

**Estimated LOC**:
- WidgetStateManager.kt: +80 lines
- HourlyGraphRenderer.kt: +400 lines (new file)
- WeatherWidgetProvider.kt: +300 lines
- WeatherWidgetWorker.kt: +10 lines
- **Total: ~790 lines**

---

## Testing Strategy

### Unit Tests
1. WidgetStateManager: view mode toggling, hourly offset bounds
2. HourlyGraphRenderer: bitmap generation, curve rendering
3. Hour label formatting (12-hour clock)
4. Hour data list building with gaps

### Integration Tests
1. Toggle between daily and hourly modes
2. Navigation in hourly mode (±6 hour jumps)
3. Data availability checks (missing hours)
4. Widget resize while in hourly mode
5. Multiple widgets with different modes

### Manual Testing Checklist
1. ✓ Install widget → default daily view
2. ✓ Tap current temp → switch to hourly view
3. ✓ Tap current temp again → switch back to daily
4. ✓ In hourly mode, tap left arrow → scroll 6 hours earlier
5. ✓ In hourly mode, tap right arrow → scroll 6 hours later
6. ✓ Resize widget in hourly mode → adapts (text vs graph)
7. ✓ Test 1-row hourly (text only)
8. ✓ Test 2+ row hourly (graph with curve)
9. ✓ Verify current time indicator visible and correct
10. ✓ Test with limited hourly data (gaps)

### Edge Cases
- No hourly data available → show message or disable hourly mode
- Partial hourly data (missing hours) → interpolate or skip
- Navigate beyond available data → disable nav buttons
- Widget resize during hourly view → preserve mode
- Multiple widgets → independent state

---

## Critical Implementation Notes

### 1. Data Availability
- HourlyForecastEntity only from **Open-Meteo** (NWS doesn't provide hourly)
- Check source when displaying hourly view
- Fallback: disable hourly mode if no data, show message

### 2. RemoteViews Limitations
- Can't use complex custom views
- Solution: render hourly graph as Bitmap (like daily graph)
- Text mode: reuse day containers with creative relabeling

### 3. Touch Target Setup
- Current temp click for toggle
- Ensure R.id.current_temp rendered on top (z-order)
- No conflict with existing settings intent

### 4. State Synchronization
- updateWidgetWithData() called from multiple places
- Always check viewMode at entry and route to correct renderer
- Risk: forgetting mode check → shows wrong view

### 5. Navigation Arrow Semantics
- Same arrows for daily (days) and hourly (hours)
- Check viewMode in handleNavigationDirect() and route
- UX: consider visual mode indicator (optional)

### 6. Time Zone Handling
- HourlyForecastEntity uses ISO 8601 strings
- Use LocalDateTime throughout, format consistently
- Risk: off-by-one-hour errors if inconsistent

### 7. Graph Rendering Performance
- 24 data points on every update
- Optimization: bitmap caching (future)
- Acceptable on modern devices

### 8. Hourly Data Retention
- Need 24 hours (6 past + 18 future)
- Currently fetching ±3 hours → extend to ±24 hours
- Modify WeatherWidgetWorker

### 9. Current Time Marker
- Graph drawn once, "now" line becomes stale
- Acceptable: within update interval (15-60 min)

### 10. Widget Resize Behavior
- User resizes widget while in hourly mode
- Solution: preserve mode (user chose hourly, keep it)
- State persists across resize

---

## Verification

### Build & Install
```bash
JAVA_HOME=/home/dcar/Downloads/high/android-studio/jbr ./gradlew installDebug
```

### Test Sequence
1. Add widget to home screen → shows daily view
2. Tap current temperature → switches to hourly view with current time centered
3. Verify hourly graph displays with:
   - Temperature curve connecting 24 hourly points
   - Vertical "NOW" indicator line
   - Hour labels (12a, 1a, 2a... 11p)
   - Temperature values at key points
4. Tap left arrow → scrolls 6 hours earlier
5. Tap right arrow → scrolls 6 hours later
6. Verify navigation bounds (can't go beyond -6/+18 hours)
7. Tap current temperature again → switches back to daily view
8. Resize widget to 1 row → verify text mode ("Now: 72° | +3h: 68°")
9. Resize widget to 2+ rows → verify graph mode
10. Add second widget → verify independent state (one daily, one hourly)

### Success Criteria
✓ View mode toggles on current temp tap
✓ Hourly graph renders with smooth curve
✓ Current time indicator visible and accurate
✓ Navigation scrolls ±6 hours correctly
✓ Text mode displays for 1-row widgets
✓ Graph mode displays for 2+ row widgets
✓ State persists across widget updates and app restarts
✓ Multiple widgets maintain independent modes
✓ No crashes on missing hourly data
