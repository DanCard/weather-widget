# Plan: Fix Hourly Graph Zoom Centering with 13-Zone Grid

The user reported that clicking on 8 am (the "NOW" line) and zooming in does not center the view correctly, appearing shifted by one hour. This is due to using an even number of tap zones (12) for a symmetric graph range (24h in WIDE mode, 4h in NARROW mode). In a 12-zone grid, the visual center (0 offset) falls on the boundary between Zone 5 (center -1h) and Zone 6 (center +1h), ensuring that "NOW" is never centered.

## Objective
Ensure that clicking the visual center of the hourly graphs (the "NOW" line) correctly centers the zoomed-in view on "NOW" (0 offset).

## Key Files
- `app/src/main/res/layout/widget_weather.xml`: Layout for tap zones.
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`: Constants and `zoneIndexToOffset` mapping logic.
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`: ID list for temperature graph zones.
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`: ID list for precipitation graph zones.
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`: ID list for cloud cover graph zones.

## Implementation Steps

### 1. Update Widget Layout
Add a 13th tap zone to the `graph_hour_zones` container in `widget_weather.xml`.
- Add `<FrameLayout android:id="@+id/graph_hour_zone_12" ... />` inside the `LinearLayout` with `android:layout_weight="1"`.

### 2. Update Constants
Update the zone count constant in `WeatherWidgetProvider.kt`.
- Change `HOUR_ZONE_COUNT` from 12 to 13.

### 3. Update Hourly Handlers
Add the new zone ID to the `HOUR_ZONE_IDS` list in each of the three graph handlers:
- `TemperatureViewHandler.kt`
- `PrecipViewHandler.kt`
- `CloudCoverViewHandler.kt`

### 4. Update Mapping Logic
Update `WeatherWidgetProvider.zoneIndexToOffset` to handle 13 zones and map the middle zone (index 6) to offset 0.
- For `WIDE` zoom (24h range): `currentHourlyOffset + 2 * (zoneIndex - 6)`
- For `NARROW` zoom (4h range): `currentHourlyOffset + Math.round((zoneIndex - 6f) / 3f)`

### 5. Update Tests
Update unit and integration tests to reflect the new 13-zone grid and its mapping.
- Update `app/src/androidTest/java/com/weatherwidget/widget/handlers/ZoomCycleTest.kt`.
- Update `app/src/test/java/com/weatherwidget/widget/WeatherWidgetProviderRobolectricTest.kt`.

## Verification & Testing
- **Manual Verification**: On the emulator, click exactly on the "NOW" line in WIDE view. Verify it appears centered in the NARROW view.
- **Manual Verification**: Verify that clicking at the very left and very right edges still zooms correctly to the start and end of the graph.
- **Automated Tests**: Run the updated `ZoomCycleTest` to ensure all 13 zones map to the expected offsets and handle base offsets correctly.
