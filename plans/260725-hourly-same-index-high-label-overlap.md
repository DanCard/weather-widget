# Hourly Same-Index High Label Overlap

## Report

On the running emulator's narrow hourly temperature graph, the left-edge forecast high label
`72°` and observed high label `71.9°` overlap heavily. When the two displayed values differ, the
lower observed value should be placed below its curve while the higher forecast value remains
above.

## Runtime Evidence

1. The emulator screenshot at 2026-07-25 21:15 shows the orange `72°` forecast label and pink
   `71.9°` observed label sharing the left edge and overlapping vertically.
2. `TempGraphRenderer` logged both labels at index 0:
   - forecast `HIGH`, display `72.0`, baseline `77.65772`, `placedAbove=true`
   - actual `ACTUAL_HIGH`, display `71.9334`, baseline `87.09163`, `placedAbove=true`
3. The emulator database contains the NWS 19:00 forecast value of `72.0°F`; the observed series is
   independently assembled from the persisted station observations.
4. `TemperatureLabelEngine.computeLeftEdgeHighOrdering` already handles a warmer left-edge
   forecast high paired with a cooler nearby observed high, but filters out pairs whose indices
   are equal. Its override is keyed only by index, so it cannot distinguish forecast and actual
   candidates that share index 0.

## Root Cause

The existing left-edge ordering policy is correct for nearby candidates at different indices but
cannot represent a role-specific placement override when both candidates occupy the same index.
The `ACTUAL_HIGH` therefore follows its general force-above path and collides with the forecast
high.

## Implementation Plan

1. Key left-edge placement overrides by candidate identity (`index` plus `TemperatureRole`) instead
   of index alone.
2. Allow same-index forecast-high/actual-high pairs. Preserve the established policy:
   - warmer forecast above and cooler actual below;
   - warmer actual retains its default above placement;
   - identical displayed values do not create a redundant ordering override.
3. Add a shared pure-JVM regression case reproducing forecast `72.0` and actual `71.9334` at index
   0, asserting the forecast label remains above and the actual label is below without overlap.
4. Run the focused shared test, the shared duration/category validation path, relevant Android
   renderer tests, and `git diff --check`.
5. Build/install the debug app on the emulator, trigger a widget repaint, and verify the placement
   through a fresh screenshot and `LabelPlacementDebug` log evidence.

## Acceptance Criteria

1. The emulator shows `72°` above the left-edge graph peak and `71.9°` below it without heavy
   overlap.
2. Placement logs report forecast `HIGH placedAbove=true` and observed
   `ACTUAL_HIGH placedAbove=false` for the same-index pair.
3. Existing nearby-index and warmer-observed behavior remains covered and passing.
4. All edited tests have exactly one duration category.

## Validation Results

Completed on 2026-07-25:

1. `:shared:test --tests 'com.weatherwidget.shared.graph.TemperatureLeftEdge*' --tests
   'com.weatherwidget.shared.graph.TemperatureLabelCollisionOrderTest'` passed.
2. `:shared:testShortShared` passed, including the shared test category validation.
3. Focused app tests passed:
   - `TemperatureGraphLabelGeneralRoboTest`
   - `TemperatureGraphRendererActualsTest`
   - `TemperatureGraphRendererLabelPlacementTest`
4. `:app:assembleDebug` passed and the APK was installed only on `emulator-5554`.
5. The post-install widget repaint logged:
   - forecast `HIGH 72.0`, index 0, baseline `77.65772`, `placedAbove=true`
   - actual `ACTUAL_HIGH 71.9334`, index 0, baseline `114.82474`, `placedAbove=false`
6. The post-install emulator screenshot confirms the orange `72°` label above the graph and the
   pink `71.9°` label below it with no overlap.
