# Plan - Fix Temperature Precision Discrepancy in Daily View

## Objective
Ensure the Daily View uses the same high-precision, live-blended current temperature as the Hourly Graph modes, eliminating the discrepancy where one shows an integer (e.g., 85) and the other shows a decimal (e.g., 85.3).

## Key Files & Context
- `WidgetRenderer.kt`: The central dispatcher for widget rendering. It currently calculates the live observation but fails to pass it to the `DailyViewHandler`.
- `DailyViewHandler.kt`: Handles Daily View rendering and falls back to database observations if the live value is missing.

## Implementation Steps
1.  **Modify `WidgetRenderer.kt`**: Update the `ViewMode.DAILY` branch in `updateWidgetWithData` to pass `lastObservedTemp = observation?.first` and `observedAt = observation?.second` to `DailyViewHandler.updateWidget`.

## Verification & Testing
1.  **Manual Verification**:
    - Set the widget to Daily View.
    - Note the today high temperature (e.g., 85).
    - Switch to Hourly Temperature View.
    - Verify the graph shows the same decimal value (e.g., 85.3) and that the high in Daily View now reflects this precision.
2.  **Regression Testing**: Ensure that switching between all view modes (Precip, Cloud, Temp, Daily) maintains temperature consistency in the header and today's extremes.
