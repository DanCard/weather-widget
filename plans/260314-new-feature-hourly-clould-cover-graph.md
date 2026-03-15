# Cloud Cover Hourly Graph — Implementation Plan

## Context
The app has hourly temperature and precipitation graphs. Users want a cloud cover percentage graph (0-100%) to visualize sky conditions over time. Activated by tapping weather icons/hour labels at the bottom of existing hourly graphs via bottom tap zones (similar to the 12 graph_hour_zone views). Cloud cover data is available from Open-Meteo (`cloud_cover` param) and from the NWS raw gridpoints endpoint (`skyCover` property).

---

## Phase 1: Data Layer

### 1.1 Add `cloudCover` to HourlyForecastEntity
- **File**: `app/src/main/java/com/weatherwidget/data/local/HourlyForecastEntity.kt`
- Add `val cloudCover: Int? = null` field (0-100%)

### 1.2 Database migration 30→31
- **File**: `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`
- Bump version to 31
- Add `MIGRATION_30_31`: `ALTER TABLE hourly_forecasts ADD COLUMN cloudCover INTEGER DEFAULT NULL`
- Register in migration chain

### 1.3 Parse cloud cover from NWS raw gridpoints endpoint
- **File**: `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt`
- Add new method `getSkyCover(gridPoint: GridPointInfo): Map<String, Int>` that calls `GET /gridpoints/{gridId}/{gridX},{gridY}` and parses the `properties.skyCover.values` array
- Each value has `validTime` (ISO 8601 interval like `"2026-03-14T14:00:00+00:00/PT1H"`) and `value` (0-100)
- Expand intervals (e.g., `PT3H` → 3 hourly entries) and return as `Map<startTimeISO, coverPercent>`
- Add `cloudCover: Int? = null` to `HourlyForecastPeriod` data class

### 1.4 Merge sky cover into hourly forecasts
- **File**: `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`
- In `fetchFromNws()`, after calling `getHourlyForecast()`, also call `getSkyCover()`
- Match sky cover values to hourly forecast periods by start time, populating `cloudCover`
- Pass through to `HourlyForecastEntity` construction

### 1.5 Parse cloud cover from Open-Meteo
- **File**: `app/src/main/java/com/weatherwidget/data/remote/OpenMeteoApi.kt`
- Add `cloud_cover` to the hourly params string
- Parse the array alongside existing hourly fields
- Pass through to entity construction

### 1.6 Add cloudCover to other API data classes
- **Files**: `WeatherApi.kt`, `SilurianApi.kt` — add `cloudCover: Int? = null` (parse if available in response)

### 1.7 Propagate through repository
- All `save*HourlyForecasts()` methods in `ForecastRepository.kt` — pass `cloudCover` when constructing `HourlyForecastEntity`

---

## Phase 2: View Mode & State

### 2.1 Add CLOUD_COVER to ViewMode enum
- **File**: `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- Add `CLOUD_COVER` after `PRECIPITATION` in `ViewMode` enum
- Add `toggleCloudCoverMode(widgetId)` method (mirrors `togglePrecipitationMode`)

---

## Phase 3: Renderer

### 3.1 Create CloudCoverGraphRenderer
- **New file**: `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`
- Template: `PrecipitationGraphRenderer.kt`
- Data class: `CloudHourData` with `cloudCover: Int` (0-100) replacing `precipProbability`
- Color scheme: gray gradient (curve ~`#888888`, fill light gray with alpha) — distinct from blue precip and orange temp
- Reuse `GraphRenderUtils`: `buildSmoothCurveAndFillPaths()`, `drawHourLabels()`, `drawNowIndicator()`, `smoothValues()`
- Weather icons at bottom, day labels, NOW indicator, percentage labels (same placement logic as precip)

---

## Phase 4: View Handler

### 4.1 Create CloudCoverViewHandler
- **New file**: `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
- Template: `PrecipViewHandler.kt`
- Use `CloudCoverGraphRenderer`
- Map `HourlyForecastEntity.cloudCover` → `CloudHourData.cloudCover` (default 0 if null)
- Same zoom tap zones, nav buttons, API toggle, settings gear

---

## Phase 5: Activation & Intent Routing

### 5.1 Add bottom tap zones to widget layout
- **File**: `app/src/main/res/layout/widget_weather.xml`
- Add bottom tap zone views (similar to `graph_hour_zone_0..11`) positioned at bottom ~40dp of graph area, overlaying weather icons / hour labels
- IDs: `graph_bottom_zone_0..11` (or fewer if appropriate)

### 5.2 Wire bottom zones in view handlers
- **Files**: `TemperatureViewHandler.kt`, `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`
- In temp/precip handlers: bottom zones send `ACTION_SET_VIEW` with target `CLOUD_COVER`
- In cloud cover handler: bottom zones send `ACTION_SET_VIEW` with target `TEMPERATURE` (cycle back)

### 5.3 Update WidgetIntentRouter
- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- Add `CLOUD_COVER` branches in: `handleSetView`, `updateHourlyViewWithData`, `handleNavigation`, `handleCycleZoom`, `handleToggleApi`, `handleResize`
- Everywhere that checks `TEMPERATURE || PRECIPITATION` also check `CLOUD_COVER`

### 5.4 Register action in WeatherWidgetProvider
- **File**: `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- Handle cloud cover actions in `onReceive`

---

## Verification

1. `./gradlew assembleDebug` — build succeeds
2. Install on emulator, add 2x3+ widget
3. Tap current temp → hourly temp graph
4. Tap weather icons/hour labels at bottom → cloud cover graph (gray curve 0-100%)
5. Tap bottom again → returns to temperature
6. Nav left/right, zoom in/out work in cloud cover mode
7. Toggle API source → cloud cover shows for both NWS (from skyCover) and Open-Meteo (from cloud_cover)
8. `./gradlew connectedDebugAndroidTest` — existing tests pass
