# Plan: Restore Staleness Indicator on Zoomed-in Hourly Graphs

The user reported that the staleness indicator (e.g., "25m", "1h 5m") next to the "Last Fetch Dot" has disappeared from the zoomed-in hourly graph. Investigation reveals that the renderer uses a hardcoded `if (hours.size <= 8)` check to detect the zoomed-in view. However, with the addition of sub-hourly measurements, even the zoomed-in (NARROW) view (which covers 4 hours) can contain over 100 points, causing the indicator to be incorrectly suppressed.

This plan replaces the count-based check with a duration-based check to correctly identify the zoomed-in state and restore the indicator.

## Objective
Restore the staleness indicator (age of last measurement) on zoomed-in Temperature and Precipitation hourly graphs by fixing the zoom-level detection logic.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Rendering logic for temperature graph age text.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Rendering logic for precipitation graph age text.

## Implementation Steps

### 1. Fix Zoom Detection in TemperatureGraphRenderer
- In `renderGraph`, replace the condition `if (hours.size <= 8)` with a duration-based check:
  `if (java.time.Duration.between(hours.first().dateTime, hours.last().dateTime).toHours() <= 12)`
- This correctly distinguishes between NARROW zoom (4h duration) and WIDE zoom (24h duration) even when high-frequency sub-hourly points are present.
- Ensure the indicator continues to be drawn without collision checks, as requested.

### 2. Fix Zoom Detection in PrecipitationGraphRenderer
- Apply the same duration-based check replacement in `PrecipitationGraphRenderer.kt`.

## Verification & Testing
- **Visual Verification**: Use the emulator to confirm that the staleness indicator reappears in the NARROW zoom view when a recent observation is present.
- **Log Review**: Confirm `CURR_STALE_DEBUG` logs show `obsAgeMin` consistent with the displayed age text.
- **Collision Check**: Verify the age text is drawn even if it overlaps with other labels near the fetch dot.
