# Remove graph header row collision checks

## User Prompts

1. "Graphs should not check if there is a collision with header row.  On hourly graph kimi said top ghost label of 67.8 on android was not drawn because collision with header row."
2. "yes"
3. "Write session summary to summaries/ dir"

## Problem & Root Cause

1. **Symptom:** On the Android hourly graph, a top ghost line label (e.g. 67.8°) was not drawn because of collision with the header row.
2. **Evidence & Root Cause:**
   - Commit `795bd21f` ("Share one top limit for every hourly-graph label") introduced `TemperatureHeaderFit.graphObstacles(...)`, mapping header items (buttons, date, source indicator, left cluster) into the graph bitmap as `TemperatureGraphObstacleType.HEADER` obstacles.
   - `TemperatureGraphAnnotationRenderer.kt` added `headerBounds` to `reservedHardBounds` for `ValueLabelEngine` and also included `HEADER` in `input.graphObstacles()`, which `GhostLineLabel.placeAll` consumes as `drawnBounds`.
   - When the ghost line peak rose to the top of the canvas, its bounding box intersected the `HEADER` obstacles in `drawnBounds`. `GhostLineLabel.placeAll` rejected the candidate as a collision, suppressing the ghost line label.
   - The user directive confirmed: graphs should not check if there is a collision with the header row.

## Changes

1. **Hourly Graph Label Placement (`TemperatureGraphAnnotationRenderer.kt`)**:
   - Removed `headerBounds` from `reservedHardBounds` passed to `TemperatureLabelEngine` (retaining `fetchDotBounds` as the only hard bounds).
   - Removed the `headerBounds` helper function.
   - `input.graphObstacles()` no longer contains header obstacles, allowing `GhostLineLabel`, `ForecastDeltaLabel`, `DominantStationLabel`, and `ActualsSourceLabel` to place freely across the full label area up to canvas top edge `0f`.
2. **Obstacle Registry & Graph Renderer (`TemperatureGraphObstacleRegistry.kt`, `TemperatureGraphRenderer.kt`)**:
   - Removed `TemperatureGraphObstacleType.HEADER`.
   - Removed `headerObstacles` parameter, `Log.v(TAG, "HeaderObstacles: ...")`, and header obstacle registration in `renderGraph`.
3. **State Resolver & Header Fit (`TemperatureStateResolver.kt`, `TemperatureHeaderFit.kt`)**:
   - Removed header obstacle calculation and parameter forwarding in `TemperatureStateResolver`.
   - Removed `graphObstacles(...)` and `drawnRightCluster` from `TemperatureHeaderFit`, leaving `TemperatureHeaderFit.resolve(...)` intact for `TemperatureViewBinder`.
4. **Shared Label Area & Header Fit (`GraphLabelArea.kt`, `HeaderFit.kt`)**:
   - Removed `GraphLabelArea.headerObstacles(...)` while preserving `GraphLabelArea.TOP = 0f` and `GraphLabelArea.rect(...)`.
   - Removed `HeaderFit.occupiedSpans(...)`.
5. **Architecture & Tests (`ARCHITECTURE.md`, `GhostLineLabelTest.kt`, `GraphLabelAreaTest.kt`, `HeaderFitTest.kt`)**:
   - Updated `arch/ARCHITECTURE.md` to document that graphs do not check for collision with the header row.
   - Removed `a header item over the peak keeps the label out` in `GhostLineLabelTest.kt`.
   - Removed `headerObstacles` tests in `GraphLabelAreaTest.kt` and `occupiedSpans` tests in `HeaderFitTest.kt`.

## Verification

- **JVM Unit Tests**: `./scripts/unit-tests.sh` ran and passed all 6,178 unit tests across `:shared`, `:desktop`, and `:app`.
- **Instrumented Emulator Tests**:
  - `com.weatherwidget.widget.TemperatureGhostLineTest` passed on the foldable emulator (`emulator-5554`).
  - `com.weatherwidget.widget.TemperatureGraphRendererRegressionTest` passed on the foldable emulator (`emulator-5554`).
