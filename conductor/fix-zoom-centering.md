# Plan: Fix Hourly Graph Zoom Centering

The user reported that clicking on the hourly temperature graph (to zoom in) does not center the view correctly. Specifically, clicking on the "now" timeline in WIDE view results in the NARROW view being shifted, with "now" appearing on the far right.

Investigation revealed that the `ZoomLevel.WIDE` configuration is asymmetric (`backHours = 8`, `forwardHours = 16`), while the tap zone calculation and the NARROW view (`backHours = 2`, `forwardHours = 2`) expect a different centering logic.

## Objective
Ensure that zooming in/out of the hourly graphs (Temperature, Precipitation, Cloud Cover) centers the view on the time corresponding to the tapped location.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`: Defines `ZoomLevel` back/forward hours.
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`: Contains `zoneIndexToOffset` mapping logic.

## Implementation Steps

### 1. Make WIDE Zoom Symmetric
Modify `ZoomLevel.WIDE` in `WidgetStateManager.kt` to be symmetric. This simplifies centering logic and aligns it with the NARROW view.
- Change `WIDE` to `backHours = 12, forwardHours = 12`. This still covers a 24-hour window but keeps `centerTime` at the visual center.

### 2. Update Tap Zone Mapping
Update `zoneIndexToOffset` in `WeatherWidgetProvider.kt` to correctly map the 12 tap zones to the new symmetric WIDE window.
- For `WIDE`: The 24-hour window (-12 to +12) should map to 12 zones of 2 hours each.
- Formula: `currentHourlyOffset + (-11 + 2 * zoneIndex)` (this centers each 2h zone).

### 3. Verify NARROW to WIDE Mapping
Ensure that zooming out from NARROW also behaves reasonably.
- The current formula for NARROW (`-2f + (zoneIndex + 0.5f) / 3f`) maps a 4-hour window to 12 zones. This seems correct for a symmetric 4h view.

## Verification & Testing
- **Manual Verification**: On the emulator, click the "now" line in WIDE view. Verify it appears in the center of the NARROW view.
- **Manual Verification**: Click the far left and far right of the WIDE view and verify the NARROW view centers on those times.
- **Regression Test**: Run `HourlyZoomCenteringRoboTest` to ensure no existing centering logic was broken.
