# Enlarge Navigation Arrow Touch Targets

## Objective
Enlarge the touch area for the right (and left) navigation arrows in the daily view to prevent misclicks on the adjacent columns.

## Proposed Solution
The widget uses invisible `FrameLayout` overlays (`nav_left_zone` and `nav_right_zone`) in `app/src/main/res/layout/widget_weather.xml` to create touch targets for the navigation arrows without affecting the visual layout. 

Currently, these zones have a `layout_width` of `32dp`. We will increase this width to `40dp` for both `nav_left_zone` and `nav_right_zone`. Since they are transparent, this will not change any visual elements, but will enlarge the clickable area, even if it slightly overlaps the outer columns.

## Implementation Steps
1. Modify `app/src/main/res/layout/widget_weather.xml`:
   - Change `layout_width` of `nav_left_zone` from `32dp` to `40dp`.
   - Change `layout_width` of `nav_right_zone` from `32dp` to `40dp`.
2. Create an automated test (`NavTouchZoneRoboTest.kt`):
   - Inflate the `widget_weather.xml` layout using Robolectric.
   - Assert that the `layoutParams.width` of `R.id.nav_left_zone` is `40dp` (converted to pixels).
   - Assert that the `layoutParams.width` of `R.id.nav_right_zone` is `40dp` (converted to pixels).

## Verification
- Run the automated test to ensure the width is set correctly.
- Deploy the widget and verify that clicking near the edges correctly triggers the navigation actions rather than clicking the adjacent day/column.
- Verify no visual changes occur.