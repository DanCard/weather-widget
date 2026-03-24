# Plan: Refactor `DailyForecastGraphRenderer.kt`

This plan addresses findings from the code review of `DailyForecastGraphRenderer.kt`, focusing on performance optimization, code modularity, and technical safety.

## Objective
- **Optimize Performance**: Cache `Paint` objects to reduce GC pressure during frequent widget renders.
- **Improve Maintainability**: Break down the monolithic `renderGraph` function into smaller, specialized functions.
- **Enhance Code Quality**: Remove dangerous `!!` assertions and consolidate duplicated rendering logic.
- **Apply UI Enhancements**: Implement the shrunk and repositioned "Today" bulb for a cleaner look.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: The target file for refactoring.

## Implementation Plan

### Phase 1: Paint Caching & Layout Constants
1.  **Define `PaintSet`**: Create a private data class `PaintSet` within the object to hold all configured `Paint` objects.
2.  **Implement Caching**: Use a cache (keyed by density and scale) to reuse `PaintSet` instances across render calls.
3.  **Clean up Constants**: Move layout-related constants to the top level of the object.

### Phase 2: Modularize `renderGraph`
1.  **Extract `computeLayout`**: A private function to calculate pixel dimensions, scale factors, and graph bounds.
2.  **Extract `drawDayColumn`**: A private function responsible for rendering a single day's label, weather icon, and temperature text.
3.  **Extract `drawTodayTripleBar`**: A private function to handle the complex triple-bar (Snapshot, Observed, Forecast) logic, consolidating the three duplicated blocks currently in the code.

### Phase 3: Logic & UI Improvements
1.  **Safety First**: Replace `!!` assertions with `?.` or safe defaults.
2.  **Shrink the Bulb**: Reduce `bulbRadius` multiplier from `1.8f` to `1.2f`.
3.  **Reposition the Bulb**: Shift the bulb's center down by `0.5f * bulbRadius` so it overlaps the stem less and looks more "attached" to the bottom.
4.  **Consolidate Comparisons**: Ensure "Snapshot" and "Forecast" overlays are drawn with consistent logic.

### Phase 4: Integration & Cleanup
1.  **Rewrite `renderGraph`**: Re-implement `renderGraph` as a thin orchestrator that calls the modular functions.
2.  **Standardize Logging**: Ensure debug logs are informative but not excessively noisy.

## Verification & Testing
- **Compilation**: Run `./gradlew assembleDebug` to ensure no regression in build.
- **Visual Regression**: Verify on the emulator that:
    - Colors remain correct (Red center, Yellow left, Blue right for Today).
    - Alignment of labels and icons is preserved.
    - The "Today" bulb looks more compact and better aligned.
- **Null Safety**: Verify that days with missing data (e.g., historical days with only partial observations) still render gracefully.
