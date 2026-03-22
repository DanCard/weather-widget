# Plan: Fix NARROW Zoom Bug on Precipitation View

## Objective
Ensure that whenever a user navigates to the Precipitation graph (either by clicking the top `precip_probability` text or by clicking a day in the daily forecast view that has a rain chance), the widget unconditionally defaults to the zoomed-out `ZoomLevel.WIDE` (24-hour) view. This fixes the reported issue where the user accidentally gets stranded in the zoomed-in `ZoomLevel.NARROW` (4-hour) view.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

## Implementation Steps
1. **Update `WidgetStateManager.togglePrecipitationMode`**:
   - Currently, it only resets the zoom to `ZoomLevel.WIDE` if `currentMode == ViewMode.DAILY`.
   - Update the logic so that if `newMode == ViewMode.PRECIPITATION`, it **unconditionally** resets the zoom to `ZoomLevel.WIDE` to prevent carrying over a `NARROW` zoom from another mode (like `TEMPERATURE`).

2. **Update `WidgetIntentRouter.handleSetView`**:
   - Currently, it only resets the zoom to `ZoomLevel.WIDE` if `previousMode == ViewMode.DAILY`.
   - Update the logic to explicitly check if `targetMode == ViewMode.PRECIPITATION`, and if so, unconditionally set the zoom to `ZoomLevel.WIDE`.

## Verification & Testing
- Deploy the widget.
- Switch to `TEMPERATURE` mode and cycle the zoom to `NARROW` (4-hour view).
- Click the top precipitation probability text. Verify the widget switches to `PRECIPITATION` mode and resets to the `WIDE` (24-hour) zoom level.
- Switch back to `DAILY` mode.
- Click a specific day in the daily forecast that has a rain chance. Verify it switches to `PRECIPITATION` mode and shows the `WIDE` zoom level for that day.
