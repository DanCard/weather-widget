# Fix Hourly Zoom Centering in Graph Views

## Summary
- Correct shared hourly zoom behavior so a tapped wide-view zone resolves to the hour the user expects, and narrow mode renders that hour in the visual center.
- Apply the fix across temperature, precipitation, and cloud-cover graphs.

## Implementation Changes
- Change `ZoomLevel.NARROW` to a symmetric 5-hour window centered on the selected hour.
- Shift `WeatherWidgetProvider.zoneIndexToOffset(...)` left by one hour so wide-zone taps align with the intended center hour.
- Keep existing zoom tap plumbing in each hourly handler and rely on the shared offset/window behavior.
- Expose the hourly graph data builders for regression testing and add tests that assert the centered label in narrow mode.

## Test Plan
- Update the zone-offset tests to the new shared mapping.
- Add a Robolectric regression test that verifies `12p` lands in the center slot for temperature, precipitation, and cloud-cover narrow windows.
- Re-run targeted zoom-cycle and provider mapping tests.

## Assumptions
- The selected/tapped hour should appear in the center of the narrow graph.
- A symmetric 5-hour narrow window is acceptable for all hourly graph modes.
