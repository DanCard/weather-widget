# Fix Samsung Hourly Graph Tap Routing

## Summary

The hourly temperature widget already binds the weather icon to the cloud cover view, but the full-screen hourly zoom overlay likely intercepts those taps first on Samsung's widget host. The fix is to restrict zoom hit targets to the graph body and reserve the hourly icon/label row for switching to cloud cover.

## Implementation Changes

- Refactor the graph-touch overlay in `app/src/main/res/layout/widget_weather.xml` so zoom targets no longer span the full widget height.
- Split the current hourly graph overlay into two interaction regions:
  - An upper graph-body region used only for zoom actions.
  - A lower icon/label row region used only for switching from hourly temperature to cloud cover.
- Update `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`:
  - In wide zoom, bind the 12 hourly tap zones only to the graph-body region.
  - In narrow zoom, bind the single zoom-out tap target only to the graph-body region.
  - Bind the lower icon/label row to `ACTION_SET_VIEW` with `ViewMode.CLOUD_COVER`.
  - Keep the top-left `weather_icon` click bound to the same cloud cover action.
- Size the lower cloud-cover tap band to match the rendered bottom hourly labels/icons row rather than a larger lower-half region, so zoom remains available in the graph body.
- Leave routing in `WidgetIntentRouter` unchanged unless the layout refactor requires shared overlay ID updates.
- Leave `CloudCoverViewHandler` behavior unchanged except for any layout ID alignment needed after the overlay split.

## Behavioral Contract

- In hourly temperature view with wide zoom:
  - Tapping the graph body zooms in.
  - Tapping hourly labels/icons switches to cloud cover.
  - Tapping the top-left weather icon switches to cloud cover.
- In hourly temperature view with narrow zoom:
  - Tapping the graph body zooms back out.
  - Tapping hourly labels/icons switches to cloud cover.
  - Tapping the top-left weather icon switches to cloud cover.
- Daily view, precipitation view, nav arrows, history, API toggle, settings, and cloud-cover return behavior stay unchanged.

## Test Plan

- Add or extend handler-level tests if the current test setup can validate `RemoteViews` click bindings after the overlay split.
- Manual validation on a Samsung device:
  - Open hourly temperature view in wide zoom and tap the graph body; confirm zoom-in.
  - Tap hourly labels/icons; confirm switch to cloud cover instead of zoom.
  - Tap the top-left weather icon; confirm switch to cloud cover.
  - In narrow zoom, tap the graph body; confirm zoom-out still works.
  - In cloud cover view, tap the weather icon; confirm return to temperature view still works.
- Regression-check the same flows on a non-Samsung host or emulator to confirm the narrowed zoom region still behaves correctly.

## Assumptions

- This should be fixed as a general widget-host touch-layering issue, not with a Samsung-only code path.
- "Hourly labels/icons" refers to the bottom row drawn by `TemperatureGraphRenderer`.
- The desired post-fix interaction is locked in: zoom is graph-body only, and the bottom label/icon row is reserved for cloud-cover switching.
