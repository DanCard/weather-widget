# Plan: Fix Missing Forecast Columns with Climate Normals Fallback

## Background & Motivation
In the Daily Forecast view, columns for future days are currently skipped if the API (e.g., NWS) does not provide a forecast for that specific date. On wide widgets (like foldables), this creates an inconsistent grid where columns on the right simply disappear. To fix this, we should fall back to **Climate Normals** (historical averages) when a live forecast is missing, ensuring the widget always fills its allocated columns.

## Proposed Solution
Modify `DailyViewLogic.prepareGraphDays` to:
1. Remove early `return@forEachIndexed` calls that skip days with missing data.
2. Implement a fallback to `climateNormals` for any future day missing a live forecast.
3. If even climate normals are missing, render an empty column (labeled) to preserve the grid structure.

### Implementation Steps

1.  **Modify `DailyViewLogic.kt` (`prepareGraphDays`)**:
    -   Remove the filtering logic that checks for `weather == null`, `actual == null`, etc., at the start of the loop.
    -   For future days (`!isToday && !isPastDate`):
        -   If `finalHigh` or `finalLow` are null (missing forecast), attempt to load from `climateNormals[MonthDay.from(date)]`.
        -   If found, set `finalHigh`/`finalLow` to the normal values and set `isClimateOverlay = true`.
    -   Ensure the day is always added to the `days` list.

2.  **Add Unit Tests**:
    -   Update `DailyViewLogicTest.kt` to verify that a future day with NO forecast data but WITH climate normals is rendered correctly as a climate fallback.
    -   Add a test for a "complete black hole" day (no forecast, no normals) to ensure it still appears in the output list (maintaining column count).

## Verification & Testing

### Automated Tests
-   `DailyViewLogicTest.kt`: Add `future day with missing forecast uses climate normals`.
-   `DailyViewLogicTest.kt`: Add `future day with no data still renders as empty column`.

### Manual Verification
1.  Open the foldable emulator with NWS (which only gives 7 days).
2.  Navigate to the right.
3.  Ensure the 8th and 9th days (e.g., next Saturday/Sunday) appear.
4.  Verify they use the "Climate Normal" style (dashed or dimmed bars, depending on renderer implementation) if normals are available.
