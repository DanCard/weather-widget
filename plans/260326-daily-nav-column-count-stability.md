# Keep Daily Graph Column Count Stable During Navigation

## Summary

Daily graph view should keep using the widget's measured column capacity while navigating left and right. The current render path appears to couple graph width and touch-zone visibility to the number of populated `DayData` items, which allows the apparent column count to change when the visible date window shifts and some dates are missing data.

## Key Changes

- In `DailyViewHandler`, treat measured `numColumns` as the layout contract for daily graph mode.
- Pass measured `numColumns` to `DailyForecastGraphRenderer.renderGraph(...)` instead of `days.size`.
- Pass measured `numColumns` to `setupGraphDayClickHandlers(...)` instead of `days.size`.
- Continue using each `DayData.columnIndex` to place populated bars and click handlers into the correct slots.
- Preserve empty slots when a visible date has no drawable data instead of collapsing the grid.
- Update the existing daily graph touch-zone instrumented test so it asserts fixed visible zones for the widget column count, with only populated columns clickable.

## Tests

- Add or update a unit/Robolectric regression test around the daily graph render path:
  - given a fixed 8- or 9-column widget,
  - and a navigation step that changes which days have data,
  - the rendered graph still uses the same number of columns.
- Keep a renderer-level test that verifies bar spacing uses widget columns rather than populated day count.
- Keep a touch-zone test that verifies all measured zones remain visible up to `numColumns`, while only populated `columnIndex` zones receive click handlers.
- Add an integration-style test for navigation stability.
  - Preferred scope: Robolectric or instrumented widget-flow test that performs two daily renders with the same widget size but different offsets/data windows and fails if the displayed column count changes.
  - This is worth having because the bug is a cross-component regression between sizing, day preparation, graph rendering, and touch-zone setup rather than a pure renderer bug.

## Assumptions

- The reported "back button" is the widget's left navigation arrow in daily graph view.
- The root cause is the daily graph path using populated day count in places where it should use measured widget columns.
- Empty visual columns are the correct behavior for a fixed-size widget when some dates in the visible window have no drawable data.
