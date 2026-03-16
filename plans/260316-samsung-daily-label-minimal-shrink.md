# Samsung Daily Graph Minimal Label Shrink

## Issue
A litle bit of font overlap on daily forecast, samsung device, left side.  Temps have decimal place.

## Summary
- Apply a small global shrink to daily graph temperature labels.
- Keep the existing day-label behavior as-is.
- Keep the existing number of visible forecast days and all daily navigation behavior unchanged.

## Key Changes
- Update `DailyForecastGraphRenderer` to reduce the base temperature-label text size a little across the board.
- Do not add any new branch based on column count.
- Leave bars, layout spacing, and column count unchanged.
- Keep the existing day-label width scaling test coverage.

## Test Plan
- Run unit tests covering the remaining day-label sizing helper.
- Run the full unit test suite to catch regressions.
- Manually verify Samsung daily forecast with WAPI selected to confirm the temp labels are visibly smaller.

## Assumptions
- The screenshot issue is primarily oversized daily temperature labels.
