# Plan: Benchmark Precipitation Graph Rendering Performance

## Objective
Identify performance bottlenecks in the precipitation graph rendering process by adding granular benchmarking logs. This will help address the user's report of slow rendering during widget updates/installation.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: The core rendering logic.
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`: The handler that calls the renderer.

## Implementation Steps

### 1. Add Benchmarking to `PrecipitationGraphRenderer.renderGraph`
Inject timing markers at key phases of the `renderGraph` function:
- **Phase 1: Setup** (Bitmaps, Paints, Layout)
- **Phase 2: Data Processing** (Scaling, Smooth Curve)
- **Phase 3: Base Drawing** (Fill, Curve, Labels)
- **Phase 4: Extremas & Candidates** (Peak/Valley detection)
- **Phase 5: Mandatory Logic** (Thinning, Clustering)
- **Phase 6: Label Placement Loop** (Overlap detection)
- **Phase 7: End of Graph & Icons** (End labels, Raindrop icons)

Modified `renderGraph` will log a summary of these phases in microseconds (μs).

### 2. Update `PrecipViewHandler.updateWidget`
Update the existing `WidgetPerfLogger` call to include more granular data if necessary, though the existing `renderMs` is already logged. The new logs in `PrecipitationGraphRenderer` will provide the "deep dive" data.

## Verification & Testing
1. Deploy the updated code to the emulator.
2. Monitor `adb logcat` for `PrecipGraphPerf` tags during widget updates.
3. Identify which phase takes the longest.
4. Compare performance across different zoom levels (WIDE vs. NARROW).
