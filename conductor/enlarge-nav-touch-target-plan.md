# Plan: Fix Navigation Touch Priority (Z-Order)

## Objective
Fix the issue where navigation arrows (left/right) are difficult to hit and often trigger the temperature graph instead. We have identified a **Z-order bug** where the navigation elements are being covered by other invisible touch zones (like the 96dp-wide current temperature header) because they are declared earlier in the XML.

As requested by the user, we will **only fix the Z-order** to see if the existing 40dp touch zones are sufficient when they are properly prioritized.

## The Bug
In a `FrameLayout`, views declared later are drawn on top and receive touch events first.
- **Current State**: Navigation buttons are declared at the top of the file.
- **Problem**: `current_temp_zone` and `api_touch_zone` are declared much later and overlap the navigation areas, "stealing" the clicks and opening the temperature graph.

## Proposed Solution
Move the navigation-related views to the very end of the `FrameLayout` to ensure they have the highest touch priority.

## Implementation Steps
1. **Relocate Navigation Views**: Move the following elements from their current locations in `app/src/main/res/layout/widget_weather.xml` to the very end of the file, just before the closing `</FrameLayout>`:
   - `nav_left` (ImageButton)
   - `nav_right` (ImageButton)
   - `nav_left_zone` (FrameLayout)
   - `nav_right_zone` (FrameLayout)

**Note**: No dimensions, padding, or other visual properties will be changed. We are only changing the declaration order to fix touch priority.

## Automated Tests (Regression Prevention)
To ensure this Z-order bug doesn't return, we will add a new Robolectric test case to **`app/src/test/java/com/weatherwidget/widget/NavTouchZoneRoboTest.kt`**.

The test will verify that the navigation touch zones are at the "top" of the layout stack (i.e., they have a higher child index in the root `FrameLayout` than the elements they were previously overlapping).

**Test Logic**:
- Inflate the `widget_weather` layout.
- Get the root `FrameLayout` (`widget_root`).
- Assert that `root.indexOfChild(view.findViewById(R.id.nav_left_zone))` is **greater than** `root.indexOfChild(view.findViewById(R.id.current_temp_zone))`.
- Assert that `root.indexOfChild(view.findViewById(R.id.nav_right_zone))` is **greater than** `root.indexOfChild(view.findViewById(R.id.api_touch_zone))`.

## Verification
- **Functional Audit**: Verify that tapping the navigation arrows now consistently navigates the dates instead of opening the temperature graph.
- **Visual Audit**: Verify that the arrows appear identically to their current state. They should now appear "on top" of any overlapping graph elements if they were previously being obscured.
- **Run Tests**: Execute `./gradlew test` and ensure the new Z-order assertions pass.