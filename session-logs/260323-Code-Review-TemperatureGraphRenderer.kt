# Code Review: TemperatureGraphRenderer.kt

## Context
Review of `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (977 lines).
This is the most complex of the three graph renderers (Cloud Cover, Precipitation, Temperature), rendering a dual-curve temperature graph with actual observations, forecast line, ghost correction line, temperature labels, fetch dot, staleness indicator, day labels, and weather icons.

## Findings

### 1. God Method — `renderGraph()` is ~790 lines (L170–L974)
**Severity: Medium**
The entire rendering pipeline lives in a single function. It handles: scaling, layout, paint creation, point computation, path building, fill drawing, ghost line drawing, forecast line, actual line, hour labels, icon drawing, temperature label placement (with extrema detection, prominence filtering, collision avoidance), day label placement, NOW indicator, and fetch dot with age text. Each of these is a conceptual phase, but they're all inline.

**Impact**: Hard to test individual phases, hard to navigate, hard to modify one phase without risk of breaking another. The 10+ test files exist partly because the function is monolithic — tests must invoke the entire render pipeline just to check label placement.

**Suggestion**: Extract named private functions for each phase (e.g., `computeScaling()`, `buildPaints()`, `computePoints()`, `drawFill()`, `drawCurves()`, `placeTemperatureLabels()`, `placeDayLabels()`, `drawFetchDot()`). Pass a lightweight render context object rather than 20+ local variables.

---

### 2. Paint Objects Allocated Every Render Call
**Severity: Medium**
Lines 238–343 create ~10 `Paint` objects on every call to `renderGraph()`. The fetch dot section (L914–950) creates 4 more. For a widget that re-renders on every update cycle, this generates significant GC pressure.

**Suggestion**: Cache paints at the `object` level (they're density-dependent, so key by density or re-configure existing paints). Or at minimum, move the static paints (that don't depend on `bitmapScale`) to lazy `object`-level properties.

---

### 3. Redundant Smoothing Computation
**Severity: Low**
Line 350: `smoothedForecastTemps = GraphRenderUtils.smoothValues(rawForecastTemps, iterations = 1)`
Line 373: `GraphRenderUtils.smoothValues(rawForecastTemps, iterations = 1)` — the exact same computation is repeated inside the `interpolatedForecastAtFetch` block. This is a second pass over the same data.

**Suggestion**: Reuse `smoothedForecastTemps` in the interpolation block instead of re-smoothing.

---

### 4. Vestigial Aliases and Dead Abstractions
**Severity: Low**
- L258: `val originalCurvePaint = actualLinePaint` — "backward-compat alias" that's never actually used elsewhere in the function.
- L398: `val smoothedActualOrForecastTemps = smoothedTruthTemps` — another alias "for backward compat" that's also unused.
- L357: `val smoothedTruthTemps = rawTruthTemps` — this used to go through smoothing but now it's a direct assignment (per the "no smoothing on truth curve" feedback). The variable name `smoothed*` is misleading.

**Suggestion**: Remove unused aliases. Rename `smoothedTruthTemps` to `truthTemps` since it's not smoothed.

---

### 5. `LocalDate.now()` Called Inside Renderer
**Severity: Low-Medium**
Line 854: `val today = java.time.LocalDate.now()` — the renderer reaches for system clock state rather than receiving it as a parameter. This makes the day-label "today" highlighting non-deterministic in tests and inconsistent with `currentTime` which IS passed in.

**Suggestion**: Derive `today` from the already-passed `currentTime.toLocalDate()`.

---

### 6. Fully-Qualified Type References Scattered Throughout
**Severity: Low (readability)**
Multiple places use `java.time.Instant`, `java.time.Duration`, `java.time.ZoneOffset.UTC`, `java.time.LocalDate`, `java.time.format.TextStyle`, `java.util.Locale` inline rather than importing them. This adds visual noise.

**Suggestion**: Add imports at the top of the file.

---

### 7. `Math.abs()` Instead of Kotlin `kotlin.math.abs()`
**Severity: Low**
Lines 599, 696, 709 use `Math.abs()` (Java static method), while line 474 uses `kotlin.math.abs()`. Inconsistent — prefer the Kotlin stdlib version throughout.

---

### 8. Label Placement Logic is Complex but Well-Structured
**Positive observation**: The label placement system (candidates with roles, bilateral prominence filtering, collision detection with icon bounds, natural-side-first with essential-force fallback) is sophisticated and well-thought-out. The debug callback system (`onLabelPlaced`, `onDayLabelPlaced`) enables comprehensive testing without bitmap parsing. This is a good design pattern.

---

### 9. Ghost Line Visibility Threshold is Hardcoded
**Severity: Low**
Line 474: `kotlin.math.abs(appliedDelta) >= 0.1f` — the threshold for showing the ghost line is a magic number. Consider extracting to a named constant like the other thresholds at the top of the file.

---

### 10. Gradient Deduplication Uses String Formatting
**Severity: Low**
Line 108: `val unique = stops.distinctBy { "%.4f".format(it.first) }` — using string formatting for float comparison. This works but is an unusual pattern. A numeric epsilon comparison would be more conventional and slightly faster.

---

### 11. No Null-Safety on `fetchTime!!`
**Severity: Low**
Line 936: `java.time.Duration.between(fetchTime!!, currentTime)` — force-unwrap inside a block that's guarded by `observedAt != null && fetchDotX != null`, but `fetchTime` is derived from `observedAt` (L360-362) so it's actually always non-null here. Still, `!!` is a code smell. The guard could be restructured to make the compiler prove non-nullity.

---

## Summary

| Category | Count |
|----------|-------|
| Structural (method size) | 1 |
| Performance (paint allocation) | 1 |
| Redundant computation | 1 |
| Dead code / misleading names | 3 |
| Correctness (clock source) | 1 |
| Style / consistency | 4 |

**Overall**: The renderer is functionally solid with thoughtful design (debug callbacks, prominence filtering, collision avoidance). The main concerns are the monolithic method size and per-render paint allocation. The label placement system is particularly well-designed for a widget context where space is constrained. The code has clearly evolved incrementally (visible in the backward-compat aliases), and would benefit from a cleanup pass to remove vestigial artifacts.
