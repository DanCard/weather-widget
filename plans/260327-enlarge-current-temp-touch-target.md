# Enlarge Current-Temperature Touch Target

## Prompt
Doesn't work well for me. Can we increase the size of that touch zone to see if that helps?

Should we have automated tests for this?

## Summary
- Enlarge the shared `current_temp_zone` overlay in `widget_weather.xml` with a moderate increase to improve hit rate for the top-left current-temperature area.
- Keep the interaction semantics unchanged across modes:
  - daily -> temperature
  - temperature -> daily
  - precipitation/cloud cover -> temperature
- Add automated coverage for what is stable and observable in tests:
  - layout sizing constants
  - click-routing behavior through existing `RemoteViews` test seams
- Keep final tap-usability validation manual on emulator/device, since the real question is whether the larger target feels easier to hit.

## Key Changes
- Update `app/src/main/res/layout/widget_weather.xml`:
  - increase `@id/current_temp_zone` width and height from the current `80dp x 56dp`
  - keep it anchored at `top|start`
  - avoid introducing margins or offsets that would shift the target away from the visible temperature cluster
- Preserve existing handler wiring in:
  - `DailyViewHandler`
  - `TemperatureViewHandler`
  - `PrecipViewHandler`
  - `CloudCoverViewHandler`
- Do not change `PendingIntent` actions, request codes, or view-mode transition logic.

## Automated Tests
- Add or extend a unit test similar to `PrecipTouchZoneTest` to assert the intended `current_temp_zone` dimensions and basic spacing assumptions against neighboring header controls.
- Add or extend a Robolectric touch-routing test to verify that taps on the current-temperature header still dispatch the correct action for each mode after the layout change.
- Prefer asserting stable invariants:
  - touch target remains at least Android-recommended tap size
  - daily mode current-temp tap still routes to `ACTION_TOGGLE_VIEW`
  - temperature mode current-temp tap still returns to daily via the same toggle path
  - precipitation and cloud-cover modes still route current-temp taps to `ACTION_SET_VIEW(TEMPERATURE)`
- Do not try to prove “easier to tap” in automation; that is a manual usability check, not a reliable unit/instrumentation assertion.

## Manual Test Plan
- On emulator/device, tap directly on the visible current temperature and slightly around it in daily mode; confirm it enters temperature view more reliably than before.
- Repeat in temperature mode; confirm the enlarged header area returns to daily/home reliably.
- Repeat in precipitation and cloud-cover modes; confirm the same area returns to temperature view.
- On narrower widget widths, verify the enlarged zone does not noticeably steal taps from:
  - precipitation percentage
  - inline home/history/current-stations controls
  - top-center home/history controls in hourly modes

## Assumptions
- A moderate increase is the right first step; if usability is still poor after testing, a second pass can grow the target further or reposition it.
- The highest-value automated tests here are routing and layout invariants, not synthetic tap-accuracy metrics.
- This is a layout-only interaction improvement; no persistence, schema, or public API changes are needed.
