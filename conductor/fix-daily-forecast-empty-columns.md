# Plan: Fix Missing Forecast Columns and Add Verification Tests

## Background & Motivation
The Daily Forecast view skips days if no forecast data (or climate normal) is available. This results in missing columns on wide widgets (like foldables) when the API (e.g., NWS) provides fewer days of data than the widget can display. To maintain a consistent grid, these missing days should be rendered as empty columns with just a day label, rather than being excluded entirely.

## Proposed Solution
Modify `DailyViewLogic.prepareGraphDays` to include days in the output even if they have no temperature or weather data, provided they fall within the range defined by `numColumns` and `skipHistory`.

### Implementation Steps

1.  **Refactor `DailyViewLogic.prepareGraphDays`**:
    -   Instead of `return@forEachIndexed` when data is missing, we will always `days.add(...)` for every offset in `dayOffsets`.
    -   If a day has no data (weather, actual, forecast, and climate normals are all null), it will be rendered with `high = null`, `low = null`, and `iconRes = 0`.
    -   Keep the existing "Today" vs day name labeling logic.
    -   Ensure `DailyForecastGraphRenderer.DayData` can handle `high = null` and `low = null` (which it currently does via `Float?`).

2.  **Add Unit Tests**:
    -   Update `DailyViewLogicTest.kt` to include a test case where a day has NO data but should still appear in the result list for a wide widget.
    -   Verify that `result.size` matches the expected number of columns regardless of data holes.

## Verification & Testing

### Automated Tests
-   Run `DailyViewLogicTest.kt`.
-   Add a new test: `future day with NO data is still visible as empty column`.

### Manual Verification
1.  Open the foldable emulator.
2.  Observe the Daily Forecast view with NWS.
3.  Ensure "Next Saturday" (and any other missing days up to the column count) shows up as a column with a "Sat" label, even if the graph bar is missing.
