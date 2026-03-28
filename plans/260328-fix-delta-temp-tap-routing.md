# Fix Delta Temp Tap Routing

## Summary
- The bug comes from the shared header using an always-active precipitation touch zone even when the precipitation label is hidden.
- The delta temperature badge is visually part of the temperature affordance, so it must always route to temperature view.
- The fix should align hitboxes with visible UI and add regression coverage for hidden and visible precipitation states.

## Key Changes
- Update the shared header interaction logic so `precip_touch_zone` is only active when the precipitation label is visible.
- Bind `current_temp_delta` to the same temperature PendingIntent as `current_temp`.
- Tighten the precipitation touch geometry in `widget_weather.xml` so the precipitation zone does not overlap the delta badge region.
- Apply the shared-header fix consistently in daily, temperature, cloud-cover, and precipitation handlers.
- Extract a small helper for the precipitation-hitbox visibility rule so it is testable without full widget rendering.

## Test Plan
- Add a pure unit test for the precipitation touch-zone visibility rule:
  - `null` -> hidden/disabled
  - `0` -> hidden/disabled
  - positive value -> visible/enabled
- Extend Robolectric routing tests to verify:
  - tapping `current_temp_delta` routes to temperature view
  - `precip_touch_zone` is hidden when header precipitation is hidden
  - visible precipitation still routes to precipitation view
- Manually verify on emulator:
  - hidden precipitation: delta tap opens temperature graph and the old precipitation area does nothing
  - visible precipitation: temp and delta open temperature, precip opens rain

## Assumptions
- Hidden precipitation means there should be no precipitation affordance and no active precipitation hitbox.
- The delta badge is part of the temperature header, not a separate navigation target.
- No changes are needed in `WidgetIntentRouter`; this is a header binding and layout fix.
