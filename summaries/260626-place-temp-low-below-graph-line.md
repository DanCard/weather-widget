# Fix: ACTUAL_LOW curve-avoidance test failures

## Issue

After implementing the tight-below-trough fallback for `ACTUAL_LOW` in the 3-day hourly temperature graph (`plans/260626-Place-temp-low-below-graph-line.md`), the unit test suite failed in both the `:shared` and `:app` modules. Specifically, two tests designed to verify this fallback failed because the placed actual low label had the reason `"below"` instead of the expected `"belowActualCurve"`:
- `TemperatureLabelCollisionOrderTest.actual low at valley with forecast curve dipping below places tight below trough with no leader`
- `TemperatureGraphLabelPlacementRobolectricTest.actual low at valley with forecast curve dipping below places tight below trough with no leader`

## Root cause

There were two distinct issues affecting the test cases:
1. **Left-Edge Exemption**: In both tests, the temperature lists had a length of 12, with the valley placed at index `5`. In the graph engine, any `ACTUAL_LOW` candidate within `8` indices of the `START` label (index `0`) is swept into `leftEdgeOrder` (the left-edge start pairing). This marks the candidate as `isCurveAvoidanceExempt = true`. Because of this exemption, the candidate bypassed `tryExactFitCurveAvoidance` completely, never ran the new fallback logic, and was placed directly by the normal loop (resulting in reason `"below"`).
2. **Incorrect Temperature/Coordinate Mapping (Robolectric)**: In the Robolectric test, the forecast temperature at the valley was set to `52f` while the actual was `50f`. Since warmer temperatures map to visually higher positions (smaller y-coordinates) on the graph, a forecast of `52f` stays above the actual trough, preventing a collision with the below-placed label. To correctly simulate a curve intrusion, the forecast temperature must be colder than `50f` (e.g. `48f`), matching the shared test.

## Fix

1. **Shift valley index**: Shifted the valley index from `5` to `13` in both tests by prepending 8 padding elements of `70f` to the temperature lists (making them 20 elements long). This successfully moved the candidate out of the left-edge start window of 8, allowing it to enter the curve-avoidance check.
2. **Correct forecast temperature**: Changed the forecast temperature at the valley index in the Robolectric test from `52f` to `48f`. This matched the shared test and ensured the forecast curve dipped below the actual trough, directly colliding with the label's bounding box and triggering the fallback.
3. **Align time boundaries**: Aligned the time boundaries (`currentTime` and `observedAt`) and loop bounds in both tests to match the new 20-element list size.
4. **Remove temporary prints**: Cleaned up the temporary `System.err.println` debug calls from `TemperatureLabelEngine.kt` to keep the build log clean.

## Verification

* Ran the full test suite using `./gradlew test` and verified that both modules `:shared` and `:app` compile and pass all tests successfully.
* Verified that the `ACTUAL_LOW` label in the test cases is successfully placed with `placedAbove = false`, `drawLeaderLine = false`, and `reason = "belowActualCurve"`.
* Committed the changes to the repository.
