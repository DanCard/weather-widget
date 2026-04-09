# Refactor Temperature Graph Renderer

## Objective
Divide the massive `TemperatureGraphRenderer.kt` file into smaller, distinct files with focused responsibilities. This will improve code readability, maintainability, and allow for easier unit testing of complex label placement logic without Android rendering dependencies.

## Key Files & Context
- **Target File:** `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (~1,100 lines)
- **New Files to Create:**
  - `app/src/main/java/com/weatherwidget/widget/TemperatureGraphModels.kt`
  - `app/src/main/java/com/weatherwidget/widget/TemperatureGraphStyle.kt`
  - `app/src/main/java/com/weatherwidget/widget/TemperatureLabelResolver.kt`

## Implementation Steps

### 1. Extract Data Models (`TemperatureGraphModels.kt`)
Create a new file for data classes and domain models currently nested within the renderer.
- Move the core domain model: `HourData`.
- Move the enumeration: `TemperatureRole`.
- Move all debug classes: `LabelPlacementDebug`, `FetchDotDebug`, `GhostLineDebug`, `ActualLineDebug`, `DayLabelPlacementDebug`, `PointsDebug`.
- Move internal rendering state classes: `RenderContextUpdate`, `Geometry`, `GraphData`, `DebugCallbacks`, `RenderContext`, `RenderTimings`.
- Ensure appropriate imports are resolved.

### 2. Extract Styles and Paints (`TemperatureGraphStyle.kt`)
Create a new file to house all mechanical styling configurations, isolating them from the orchestration logic.
- Move sizing, padding, threshold, and color constants (e.g., `COLD_THRESHOLD`, `TEMP_LABEL_SIZE_DP`, `COLOR_HOT`).
- Move the `PaintSet` data class and the `ensurePaints` factory method.
- Move color/gradient utility functions: `tempToColor`, `blendColors`, `buildTempGradient`, `withAlpha`.
- Move font metric helpers: `fontAscent`, `fontDescent`.

### 3. Extract Label Resolution Logic (`TemperatureLabelResolver.kt`)
Create an object to handle the business logic of determining which labels to show and how to prevent clutter/redundancy.
- Move the suppression logic functions: `checkLeftEdgeSuppression`, `checkFetchDotSuppression`, `checkRedundantPairSuppression`, `checkTransitionBoundarySuppression`, `checkEndpointSuppression`.
- Move anchor generation and candidate collection functions: `resolveExtremaRole`, `buildPotentialAnchors`, `deduplicateAnchors`, `collectLabelCandidates`, `sortLabelCandidates`.

### 4. Strip Down Main Renderer (`TemperatureGraphRenderer.kt`)
Update the `TemperatureGraphRenderer` to act solely as a clean orchestrator.
- It will now only contain the high-level drawing steps: `renderGraph`, `computePoints`, `drawFillAndCurves`, `drawHourLabelsAndIcons`, `placeTemperatureLabels` (delegating to the resolver), `placeDayLabels`, and `drawFetchDot`.
- Update all references in the main renderer to call into `TemperatureGraphStyle`, `TemperatureGraphModels`, and `TemperatureLabelResolver` as necessary.

## Verification & Testing
1. **Compilation Check:** Confirm the project compiles successfully after moving the classes and logic around.
2. **Unit Tests:** Run `./gradlew test` to ensure that no logic regressions occurred in pure functions.
3. **Instrumented Tests:** Run `./scripts/emulator-tests.sh` to ensure layout definitions and rendering logic remain stable.
4. **Visual Verification:** Check the emulator or generated test screenshots to guarantee that the refactoring was purely structural and the rendered graph is pixel-perfect identical to the previous implementation.