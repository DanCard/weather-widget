# Plan - Reduce Separation for Peak Labels in Hourly Temperature Graph

Reduce the vertical separation between temperature labels (peaks, valleys, etc.) and the temperature curve in the hourly graph for a tighter, more professional look.

## Objective
The current 3dp separation between the temperature line and its labels is perceived as too large. This plan reduces that gap to 1dp (approaching the "one pixel" suggestion while remaining density-independent) and cleans up unused variables.

## Key Files & Context
- **File**: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- **Variables**: `aboveGap`, `belowGap`, `labelTopPadding`.

## Implementation Steps

### 1. Adjust Label Gaps in TemperatureGraphRenderer
- Locate the `renderGraph` method in `TemperatureGraphRenderer.kt`.
- Change `aboveGap` from `dpToPx(context, 3f)` to `dpToPx(context, 1f)`.
- Change `belowGap` from `dpToPx(context, 3f)` to `dpToPx(context, 1f)`.
- Remove the unused `labelTopPadding` variable (currently set to `2f`).

## Verification & Testing

### Manual Verification
- Deploy the app to an emulator or device.
- Observe the hourly temperature graph.
- Verify that peak labels (like the 81.0 and 76.0 mentioned) are noticeably closer to the temperature line.
- Ensure that the labels still do not overlap the line in a way that makes them unreadable.

### Automated Testing
- Run existing unit tests for `TemperatureGraphRenderer` to ensure no regressions in label placement logic or collision detection.
- Note: Collision detection uses these gaps to define bounding boxes, so labels will now be allowed to be closer to each other vertically as well, which is expected.
