# Hourly Graph Bottom-Row: Icon-Dependent Click Routing

## Context

On hourly graphs, the bottom row shows weather condition icons per hour. Currently a single `graph_bottom_zone` (56dp overlay) routes all taps to a hardcoded destination (e.g., always cloud cover from temperature view). Two problems:
1. The zone doesn't properly intercept taps — zoom zones steal them
2. Routing should be icon-dependent: each icon should navigate to its "home" graph, or zoom if already there

### Routing Rules

| Icon Type | "Home" Graph | On home graph | On other graph |
|-----------|-------------|---------------|----------------|
| Rain/storm/snow | Precipitation | Zoom toggle | → Precipitation |
| Cloud/mostly-clear | Cloud Cover | Zoom toggle | → Cloud Cover |
| Clear/sunny/other | Temperature | Zoom toggle | → Temperature |

Uses existing `WeatherIconMapper.isRainy()` and `WeatherIconMapper.isCloudForecastEligible()` (which already includes `ic_weather_mostly_clear`).

## Changes

### 1. Routing helper — `DayClickHelper.kt`

Add `resolveHourlyBottomRowAction()`:
```kotlin
fun resolveHourlyBottomRowAction(
    iconRes: Int?,
    currentView: ViewMode,
): ViewMode? {
    if (iconRes == null) return null  // no icon → zoom
    val iconHome = when {
        WeatherIconMapper.isRainy(iconRes) -> ViewMode.PRECIPITATION
        WeatherIconMapper.isCloudForecastEligible(iconRes) -> ViewMode.CLOUD_COVER
        else -> ViewMode.TEMPERATURE
    }
    return if (iconHome == currentView) null else iconHome  // null = zoom
}
```

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`

### 2. Layout — add `graph_bottom_hour_zones`

Add a horizontal LinearLayout with 13 child FrameLayouts (`graph_bottom_hour_zone_0` through `graph_bottom_hour_zone_12`), mirroring the existing `graph_bottom_day_zones` pattern but sized to match the hourly graph footer:

- Height: 56dp (same as existing `graph_bottom_zone`)
- `layout_gravity="bottom"`, margins matching `graph_bottom_zone`
- Each child: `layout_width="0dp"`, `layout_weight="1"`, `layout_height="match_parent"`
- Placed AFTER `graph_bottom_zone` in the XML (higher z-order)
- Default visibility: `gone`

**File:** `app/src/main/res/layout/widget_weather.xml`

### 3. Request codes — `WidgetRequestCodes.kt`

Add `BASE_BOTTOM_HOUR_CLICK = 3000` and:
```kotlin
fun bottomHourClick(id: Int, index: Int) = id * 10000 + BASE_BOTTOM_HOUR_CLICK + index
```

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/WidgetRequestCodes.kt`

### 4. Shared setup — extract `setupBottomHourZones()`

Create a shared function (companion-level or top-level in a new/existing file) that all three hourly view handlers call. It takes the hour data list (which already has `iconRes` per hour), the current `ViewMode`, and wires each zone:

```
For each of the 13 zones (matching graph_hour_zone alignment):
  - Map zone index → hour in the data list (using same zoneIndexToOffset logic)
  - Look up the iconRes for that hour
  - Call resolveHourlyBottomRowAction(iconRes, currentViewMode)
  - If null (zoom): create ACTION_CYCLE_ZOOM intent with appropriate center offset
  - If ViewMode: create ACTION_SET_VIEW intent with that target
  - Set PendingIntent on the zone using bottomHourClick(appWidgetId, i) request code
```

Hide `graph_bottom_zone` (replaced by per-hour zones). Show `graph_bottom_hour_zones`.

### 5. Update view handlers

Each handler already builds hour data with `iconRes` before rendering. After the graph render, call the shared `setupBottomHourZones()`.

- **TemperatureViewHandler.kt** — `graphHours` list available at line ~368; pass `ViewMode.TEMPERATURE`
- **CloudCoverViewHandler.kt** — has equivalent hour data; pass `ViewMode.CLOUD_COVER`
- **PrecipViewHandler.kt** — has equivalent hour data; pass `ViewMode.PRECIPITATION`

Also hide `graph_bottom_day_zones` in all hourly handlers (already done for day_zones, need to add for bottom_day_zones).

## Test Plan

### Unit tests — `DayClickHelperTest.kt`

Add tests for `resolveHourlyBottomRowAction()`:
- Rain icon + PRECIPITATION view → null (zoom)
- Rain icon + TEMPERATURE view → PRECIPITATION
- Rain icon + CLOUD_COVER view → PRECIPITATION
- Cloud icon + CLOUD_COVER view → null (zoom)
- Cloud icon + TEMPERATURE view → CLOUD_COVER
- Mostly-clear icon + CLOUD_COVER view → null (zoom)
- Mostly-clear icon + TEMPERATURE view → CLOUD_COVER
- Clear icon + TEMPERATURE view → null (zoom)
- Clear icon + CLOUD_COVER view → TEMPERATURE
- null icon → null (zoom)

### Verification

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DayClickHelperTest"`
2. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug` and test on device:
   - On temp graph: tap a clear-sky icon → should zoom
   - On temp graph: tap a cloudy/mostly-clear icon → should switch to cloud cover
   - On temp graph: tap a rain icon → should switch to precipitation
   - On cloud graph: tap a cloud icon → should zoom
   - On cloud graph: tap a clear icon → should switch to temperature
   - On precip graph: tap a rain icon → should zoom
   - On precip graph: tap a cloud icon → should switch to cloud cover
