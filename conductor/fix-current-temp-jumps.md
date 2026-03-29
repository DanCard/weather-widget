# Plan: Fix Current Temperature Jumps (NWS Scope)

Address the issue where the current temperature in the widget header changes when toggling between Daily and Hourly views or when navigating the hourly graph.

## Objective
The header temperature should remain consistent and stable regardless of the widget's view mode (Daily vs Hourly), zoom level (Wide vs Narrow), or scroll position. 

### Root Cause
The current header resolution logic in `WidgetIntentRouter.resolveGraphStyleCurrentTemp` uses the same hourly forecast data that is loaded for the graph display. 
- In **Daily View**, a large 12-hour history is loaded, allowing for accurate observation extrapolation.
- In **Hourly View (Narrow Zoom)**, only a 2-hour history is loaded.
- In **Hourly View (Scrolled)**, only a 1-hour window around "now" is loaded.
When the history is insufficient, the sophisticated `ObservationBlender` fails to extrapolate stale observations, causing a jump back to raw data or a less accurate estimate.

## Proposed Changes

### 1. Robust Header Resolution in `WidgetIntentRouter.kt`
- **Introduce `resolveHeaderBlendedTemp`**:
    - This new method will be dedicated to resolving the current temperature for the widget header.
    - It will independently fetch a stable 12-hour lookback window of both observations and hourly forecasts from the database, ensuring it always has enough context for high-quality IDW blending and extrapolation.
    - It will always center its window on `LocalDateTime.now()`, making it immune to graph scrolling.
- **Update Callers**:
    - Modify `refreshDailyView` to call `resolveHeaderBlendedTemp`.
    - Modify `updateHourlyViewWithData` to call `resolveHeaderBlendedTemp`, passing `now` instead of the graph's `centerTime`.

### 2. Consistency Audit
- Ensure that both Daily and Hourly views use the same resolution source for the "Current Temp" display to eliminate any remaining discrepancies.

## Implementation Steps

1. **Modify `WidgetIntentRouter.kt`**:
    - Implement `resolveHeaderBlendedTemp` fetching its own 12h-lookback data.
    - Update `refreshDailyView` to call the new method.
    - Update `updateHourlyViewWithData` to call the new method.
2. **Cleanup**:
    - Remove the now-obsolete `resolveGraphStyleCurrentTemp`.

## Verification & Testing
- **Manual Verification**:
    - Set widget to NWS.
    - Observe current temp in Daily view.
    - Click current temp to toggle to Hourly view. Verify the temperature DOES NOT change.
    - Scroll Hourly graph to the future. Verify the header temperature remains stable.
    - Cycle zoom levels in Hourly view. Verify the header temperature remains stable.
- **Automated Test**:
    - Add a test case to `app/src/test/java/com/weatherwidget/widget/HeaderStabilityTest.kt` that simulates different forecast window sizes and verifies the header resolution remains identical if the "now" context is preserved.
