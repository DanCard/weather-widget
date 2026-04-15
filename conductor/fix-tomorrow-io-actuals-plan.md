# Plan: Fix Tomorrow.io Actuals and Graph Lines

## Objective
1.  **Fix Missing Actuals (Solid Pink Line):** Enable Tomorrow.io observation mapping so the solid "actuals" line appears in the history section.
2.  **Clarify Graph Lines:** Address why the graph shows the latest forecast for the past instead of a historical one.

## Proposed Changes

### 1. Data Mapping (Fix Missing Actuals)
The Tomorrow.io integration is missing from several source mapping utilities, causing observation data to be miscategorized as NWS and filtered out when Tomorrow.io is selected.

- **`app/src/main/java/com/weatherwidget/data/model/WeatherSource.kt`**:
    - Update `fromDisplaySourceOrNull` to include `TOMORROW_IO`.
    - Update `fromId` to include `TOMORROW_IO`.
- **`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`**:
    - Update `inferSource` to recognize the `TOMORROW_IO` station ID prefix.

### 2. Graph Rendering (Forecast vs. Actuals)
The user noticed that the history section only has one line (the dashed forecast line) instead of both a "forecast for history" and an "actuals" line.

- **Why only one line?** Currently, Tomorrow.io actuals are being filtered out (see point 1). Once fixed, the **solid hot pink line** (actuals) will appear.
- **Latest Forecast vs. Historical Forecast:** The dashed line in the history currently represents the *latest* forecast for those past hours. True "historical forecasts" (what was predicted 24h ago for right now) are not yet stored at the hourly level (only daily).
- **Proposed Future Enhancement (Optional):** We could consider storing hourly snapshots to show a true historical forecast vs. actuals comparison on the widget.

## Verification
1.  **Unit Tests:** Run `ObservationResolverTest` and `WeatherSourceTest` (if they exist) or verify with new test cases.
2.  **Visual Verification:** Refresh the widget with Tomorrow.io selected and verify that the solid hot pink line appears to the left of "NOW".
