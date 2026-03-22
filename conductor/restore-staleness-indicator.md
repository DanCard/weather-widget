# Plan: Restore Staleness Indicator on Hourly Graphs

The user reported that the staleness indicator (e.g., "25m", "1h 5m") next to the current temperature dot on the hourly graph has disappeared. Investigation reveals that this indicator is currently restricted to views with 8 or fewer hours, which covers the NARROW zoom (5 hours) but excludes the WIDE zoom (25 hours). Furthermore, if the last measurement occurred outside the NARROW window, it won't show there either.

This plan restores the indicator by increasing the hour limit to 25, allowing it to appear in WIDE zoom as well.

## Objective
Restore the staleness indicator (age of last measurement) on both Temperature and Precipitation hourly graphs and ensure it is visible in WIDE zoom.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Contains the rendering logic for the temperature graph staleness indicator.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Contains the rendering logic for the precipitation graph staleness indicator.

## Implementation Steps

### 1. Update TemperatureGraphRenderer
- Modify the condition `if (hours.size <= 8)` to `if (hours.size <= 25)` in `renderGraph` to allow the staleness indicator to be drawn in WIDE zoom.
- Ensure the indicator is drawn last to remain on top of other elements, as is currently the case.

### 2. Update PrecipitationGraphRenderer
- Modify the condition `if (hours.size <= 8)` to `if (hours.size <= 25)` in `renderGraph` for consistency.

## Verification & Testing
- **Visual Verification**: Switch between NARROW and WIDE zoom on both Temperature and Precipitation graphs to confirm the staleness indicator appears when the last measurement dot is within the visible window.
- **Manual Check**: Verify that the indicator is legible and correctly positioned next to the "Last Fetch Dot".
- **Collision Check**: Confirm that the indicator remains visible even if it overlaps with other labels (as requested by the user).
