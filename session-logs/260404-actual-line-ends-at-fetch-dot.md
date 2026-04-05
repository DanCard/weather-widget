# 2026-04-04 Session Log: Actual Line Ends at Fetch Dot

## Session Summary
1. Investigated a visual regression reported from the emulator: the hourly temperature graph's solid actual line did not end on the last observation dot.
2. Traced the issue to the renderer using a full smoothed actual path plus `clipRect`, which stopped the line at roughly the correct X but did not guarantee the visible path endpoint matched the fetch dot geometry.
3. Confirmed that upstream observation anchoring was already mostly correct: `observedAt` was being sourced from graph-style current temperature resolution and tests already distinguished `isObservedActual` from carry-forward `isActual`.
4. Wrote a focused implementation plan in `plans/260404-actual-line-ends-at-fetch-dot.md`.
5. Changed `TemperatureGraphRenderer` so the solid actual line is rendered from a dedicated anchored path that explicitly terminates at the fetch dot / last real observed anchor instead of relying on clipping the full path.
6. Added renderer debug coverage for the actual line endpoint and expanded graph regression tests to assert the solid actual line really ends on the dot.
7. Ran targeted renderer tests, broader temperature test suites, and the full `testDebugUnitTest` suite successfully.

## User Prompts Used In This Session
1. `emulator : actual temps line graph messed up: actual line should end on last observation dot`
2. `I don't understand the root cause.  Can you explain in more words?`
3. `implement plan`
4. `Write plan to plans/ dir and continue`
5. `write detailed session log to session-logs/ dir`

## Problem Statement

The user reported a visual mismatch on the emulator:

- the fetch dot correctly marked the last observation
- but the solid yellow actual line did not appear to end on that dot

The intended behavior is:

1. the solid actual line should stop at the last real observation anchor
2. the fetch dot should sit exactly on that terminal point
3. later carry-forward actual buckets should not extend the visible line past the dot

## Investigation

### Relevant Files Read
1. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
2. `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt`
3. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
4. `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
5. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt`
6. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphJunctionTest.kt`
7. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererContinuityTest.kt`
8. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererWapiTest.kt`
9. `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureFetchDotUpdateRoboTest.kt`

### Existing Renderer Behavior
The relevant renderer flow before the fix:

1. `computePoints()` built `originalPoints` for the full actual series.
2. That series used `actualTemperature ?: (forecast + delta)`, so it covered:
   1. real observed actuals
   2. carry-forward synthetic actual buckets
3. `GraphRenderUtils.buildSmoothCurveAndFillPaths()` built one full `originalPath` across that whole series.
4. `drawFillAndCurves()` rendered the solid actual line by:
   1. computing `transitionX`
   2. drawing the full `originalPath`
   3. clipping it with `clipRect(0, 0, transitionX, height)`

### Why This Was Wrong
The clipping approach only constrained the visible path horizontally.

It did **not** ensure that:

1. the path’s terminal control geometry was anchored to the fetch dot
2. the endpoint Y at the cut matched `lastObservedTemp`
3. later carry-forward points stopped influencing the visible spline shape near the end

So even when the dot itself was correct, the line could visibly end above or below it because the user was seeing the clipped edge of a longer spline, not a path whose final point was the dot.

### Upstream Anchor Check
I also checked whether `observedAt` itself was wrong.

Findings:

1. `WidgetIntentRouter.resolveGraphStyleCurrentTemp()` already uses `ObservationBlender.resolveCurrentObservation()`
2. `TemperatureHourDataBuilder` already marks real observed points with `isObservedActual = true`
3. Existing tests already asserted:
   1. the fetch dot anchor should remain at the last raw observed timestamp
   2. carry-forward buckets should not become the observed anchor

So the main problem was renderer geometry, not the data source selection.

## Root Cause

The root cause was a mismatch between:

1. how the fetch dot was positioned
2. how the solid actual line was terminated

More specifically:

1. The fetch dot used `lastObservedTemp` and `observedAt`, which represent the real observation anchor.
2. The solid actual line used a full spline influenced by all actual buckets, including carry-forward ones.
3. The renderer then clipped that full spline at `transitionX`.
4. Clipping at the right X does not mean the visible path endpoint is the same point as the dot.

There was also a secondary issue:

1. `rawTransitionX` preferred the last `isActual` bucket X.
2. That could lag behind the true observed anchor when the fetch point was between hours.
3. The fix therefore needed both:
   1. a true anchored actual path
   2. better anchor selection that prefers `fetchDotX`, then `last isObservedActual`, then a broader fallback

## Plan

Written to `plans/260404-actual-line-ends-at-fetch-dot.md`.

### Design Decisions
1. Keep upstream observation resolution unchanged unless tests prove it wrong.
2. Fix the bug in `TemperatureGraphRenderer`, not in the observation pipeline.
3. Preserve carry-forward `isActual` data for continuity and label logic, but do not let it extend the visible solid line.
4. Add a dedicated debug hook for the actual line endpoint so tests can assert real rendered geometry instead of inferring it from `clipRect` alone.

## Implementation

### 1. Added Plan File

Created:

- `plans/260404-actual-line-ends-at-fetch-dot.md`

It captures:

1. summary of the bug
2. renderer-side implementation approach
3. test coverage needed
4. assumptions about upstream anchor selection

### 2. Renderer Changes

Updated:

- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

#### New Debug Type
Added:

- `ActualLineDebug`

Fields:

1. `endX`
2. `endY`
3. `pointCount`
4. `anchoredToFetchDot`

This made the renderer testable at the actual line endpoint level.

#### New Anchored Actual Path
Added:

1. `buildAnchoredActualPoints(...)`
2. `interpolateYAtX(...)`

Behavior:

1. Compute a dedicated set of visible actual points that stops exactly at the anchor.
2. If the anchor is the fetch dot and `lastObservedTemp` is known:
   1. end the line at `fetchDotX`
   2. compute terminal Y from `lastObservedTemp`
3. Otherwise:
   1. end at the best available actual anchor X
   2. linearly interpolate Y from neighboring original points

This produces an actual path that genuinely ends at the dot instead of a full path that is merely clipped nearby.

#### Raw Anchor Selection Fix
Updated `computePoints()` anchor selection:

Old order:

1. last `isActual` bucket
2. then clipped against `nowX` / `fetchDotX`

New order:

1. `fetchDotX` if present
2. else last `isObservedActual` point
3. else last `isActual` point

This corrected the sub-hourly case where the true observation anchor falls between graphed hour buckets.

#### Drawing Change
Changed the solid actual line render path:

1. keep existing fill, forecast line, ghost line, and fetch dot behavior
2. keep `clipRect` behavior for compatibility with existing tests
3. replace `ctx.originalPath` with `ctx.actualPath` when drawing the solid actual line

So the line is now both:

1. geometrically anchored
2. still constrained to the left-of-anchor region

### 3. Test Changes

Updated:

- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphJunctionTest.kt`
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt`

#### TemperatureGraphJunctionTest
Strengthened the test from “fetch dot Y resolves” to:

1. capture `FetchDotDebug.fetchY`
2. capture `ActualLineDebug.endY`
3. assert they are equal

This directly verifies the visual bug reported by the user.

#### TemperatureGraphRendererActualsTest
Added:

- `actual line geometry ends at fetch dot not later carry-forward actual bucket`

It asserts:

1. `ActualLineDebug.endX == FetchDotDebug.fetchDotX`
2. `ActualLineDebug.endY == FetchDotDebug.fetchY`
3. the endpoint is near the real observed anchor, not later synthetic/carry-forward actual buckets

## Debugging Iterations During Implementation

### First Compile Failure
After the initial renderer patch:

1. `computePoints()` referenced `lastObservedTemp`
2. but the parameter had not been threaded through the call chain

Fix:

1. added `lastObservedTemp` to `computePoints(...)`
2. updated the `renderGraph(...)` call site

### First Test Failure
After the compile fix, targeted tests failed in:

- `TemperatureGraphJunctionTest`

Observed mismatch:

1. fetch dot Y was correct
2. actual line endpoint Y was still wrong

This exposed a second issue:

1. `rawTransitionX` was still preferring the last `isActual` bucket
2. in a sub-hourly observation case, that meant the line ended at the wrong X before the anchored path logic even had the correct terminal position

Fix:

1. changed `rawTransitionX` selection order to prefer `fetchDotX`
2. then `last isObservedActual`
3. then fallback to `last isActual`

That resolved the remaining renderer mismatch.

## Verification

### Targeted Renderer Tests
Ran:

1. `./gradlew testDebugUnitTest --tests 'com.weatherwidget.widget.TemperatureGraphRendererActualsTest' --tests 'com.weatherwidget.widget.TemperatureGraphJunctionTest' --tests 'com.weatherwidget.widget.TemperatureGraphRendererContinuityTest' --tests 'com.weatherwidget.widget.TemperatureGraphRendererWapiTest'`

Result:

1. initial compile failure fixed
2. one failing junction test exposed anchor-selection bug
3. after anchor fix, all targeted renderer tests passed

### Broader Temperature Tests
Ran:

1. `./gradlew testDebugUnitTest --tests 'com.weatherwidget.widget.*Temperature*' --tests 'com.weatherwidget.widget.handlers.Temperature*'`

Result:

1. all matching temperature-related tests passed

### Full Unit Suite
Ran:

1. `./gradlew testDebugUnitTest`

Result:

1. full unit test suite passed

## Files Changed In This Session

1. `plans/260404-actual-line-ends-at-fetch-dot.md`
2. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
3. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphJunctionTest.kt`
4. `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt`

## Final Outcome

The bug was fixed by changing the renderer from:

1. drawing a full actual spline
2. clipping it at the anchor

to:

1. computing a dedicated anchored actual path
2. explicitly ending that path at the fetch dot / last real observed anchor

This ensures:

1. the solid actual line ends on the last observation dot
2. carry-forward actual buckets do not visually extend the line past the dot
3. sub-hourly fetch anchors between hour buckets are handled correctly
4. existing ghost-line and fetch-dot continuity behavior remains intact

## Commits

No commit was created in this session.
