# Text-mode widget: NWS label overlapped the settings gear

## Symptom

On `emulator-5554`, the one-row text-only widget drew the "NWS" API indicator and the settings
gear icon on top of each other in the top-right corner. NWS was also clipped at the right edge.

## Root cause

In `app/src/main/res/layout/widget_weather.xml`, `text_mode_api_source_container` and
`text_mode_settings_icon` were both `layout_gravity="top|end"` siblings of the root `FrameLayout`,
with margins differing by only 4dp. A `FrameLayout` stacks children rather than flowing them, so
they landed in the same pixels.

The XML comment above the gear even claimed it was "placed at the bottom right" — only its *touch
zone* (`text_mode_settings_touch_zone`) was at `bottom|end`; the icon itself had drifted to the top
corner.

## Fix

Moved the API indicator and its touch zone to `center_vertical|end`, and reordered the block so the
API views are declared **after** the settings catch-all zone.

The ordering matters: the gear's 44x50dp invisible catch zone at `bottom|end` now shares a vertical
band with the centred NWS zone, and in a `FrameLayout` the later-declared child wins the tap.
Without the reorder, the bottom half of the NWS label would silently open Settings instead of
toggling the API source.

Also dropped dead `paddingTop="-4dp"` / `paddingBottom="-4dp"` attributes on the label.

## Tests

Added to `app/src/test/java/com/weatherwidget/widget/SettingsTouchZoneRoboTest.kt`, all confirmed
to fail against the old layout before the fix was restored:

- `text mode api indicator is center vertical end and gear stays top end` — pins the gravities.
- `text mode api indicator does not overlap the settings gear` — inflates and lays the widget out at
  a realistic 320x110dp single-row size, asserts the label's top clears the gear's bottom.
- `text mode api indicator sits near the vertical middle` — label centre lands in the middle third;
  touch zone tracks the label within 2px.
- `text mode api touch zone wins z-order over settings catch-all zone` — guards the
  declaration-order dependency above.

Updated the pre-existing `text mode api touch zone is top end` test to expect the new gravity.

## Gotcha: Robolectric has no font engine

The first version of the overlap test asserted `!Rect.intersects(apiLabel, gear)` on the `TextView`
itself, and it **passed against the buggy layout**. Robolectric measured the "NWS" `TextView` as 3px
wide (`Rect(313, 8 - 316, 49)`), and the degenerate rect intersected nothing.

Any Robolectric assertion whose truth depends on **text width** is decorative. The rewrite asserts on
the *container* (fixed dp) and on **vertical bands** only — not a weaker test here, because both
views are anchored to `end` and so always overlap horizontally; the vertical band is the entire
question.

Same trap as the renderer tests where `measureText` returns 0
(see `renderer_test_color_is_zero` memory). Always check a new layout test can actually fail.

## Verified

`./gradlew installDebug` + `ACTION_REFRESH` broadcast on `emulator-5554`, screenshot confirms gear
alone in the top-right corner, NWS centred vertically at the right edge, no overlap, not clipped.
