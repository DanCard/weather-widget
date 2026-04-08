# Code Review: TemperatureGraphRenderer.kt

**Date:** 2026-04-08
**File:** `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
**Lines:** 1573
**Type:** Singleton `object` — renders hourly temperature graph as a `Bitmap` for the widget.

## Status

- **#1 (println in prod)** — FIXED, committed as `120f1c0`
- **#2 (paint textAlign mutation)** — FIXED, committed as `120f1c0`
- Items #3–#16 remain open

---

## Bugs

### #3 Dead branch in `buildAnchoredActualPoints` (lines 550-554)

Both branches are identical:

```kotlin
if (visible.isEmpty()) {
    visible += terminalPoint
} else {
    visible += terminalPoint  // identical
}
```

Should just be `visible += terminalPoint` unconditionally.

### #4 Unreachable branch in `interpolateYAtX` (line 587)

`afterIndex == -1` is already covered by the preceding `afterIndex <= 0` branch, making it dead code.

---

## Architecture / Maintainability

### #5 `placeTemperatureLabels` is ~460 lines (lines 682-1145)

Handles candidate identification, deduplication, suppression, collision detection, displacement, and forced placement in a single method. Extracting phases (e.g., `collectCandidates`, `filterRedundant`, `placeLabels`) would make each concern independently testable.

### #6 1573-line singleton object

The file mixes scaling, layout, path computation, gradient building, label placement, day labels, fetch dot rendering, icon drawing, and extrema detection. Several of these are self-contained enough to extract into separate files.

### #7 Large parameter/field counts

| Structure | Field/Param Count |
|---|---|
| `RenderContext` | 34 fields |
| `renderGraph()` | 16 parameters |
| `computePoints()` | 14 parameters |

A builder or phased construction pattern would help. `RenderContextUpdate` partially addresses this but unpacks into `RenderContext` field-by-field (lines 1519-1528).

### #8 Role strings as raw string literals

Roles like `"HIGH"`, `"LOW"`, `"FORECAST_HIGH"`, etc. appear in ~40+ comparisons across the file. A sealed class or enum would provide:
- Compile-time exhaustiveness checking
- Typo prevention
- IDE usage tracking

---

## Code Duplication

### #9 Fetch dot age label formatting duplicated

Age label formatting logic appears identically in:
- `computeFetchDotBounds` (lines 1237-1239)
- `drawFetchDot` (lines 1273-1276)

Extract to a shared function like `formatAgeLabel(ageMinutes, hours)`.

### #10 `fetchY` computation repeated 3 times

```kotlin
graphTop + graphHeight * (1 - (lastObservedTemp - minTemp) / tempRange)
```

Appears at lines 607, 1207, 1264. Extract to a `tempToY(temp)` helper.

### #11 Six near-identical suppression blocks (lines 860-918)

The pattern "if role == X and counterpart Y exists and values are close, suppress" repeats for:
- `ACTUAL_HIGH` vs `HIGH`
- `ACTUAL_LOW` vs `LOW`
- `FORECAST_HIGH` vs `ACTUAL_HIGH`
- `FORECAST_LOW` vs `ACTUAL_LOW`
- `PAST_FORECAST_HIGH` vs `ACTUAL_HIGH`
- `PAST_FORECAST_LOW` vs `ACTUAL_LOW`

A single parameterized function like `suppressIfRedundant(role, counterpartRole, ...)` could replace all six.

---

## Performance

### #12 `buildSmoothCurveAndFillPaths` called 4 times per render (lines 472, 474, 476, 518)

Each call allocates `Path` objects and computes Catmull-Rom tangents. For widgets that redraw every 15-60 min, repeated allocation may cause GC pressure. Consider reusing `Path` objects via `reset()`.

### #13 `PathMeasure` allocated inside a loop (line 632)

A new `PathMeasure` is created per segment for dash phase accumulation. A single reusable instance with `setPath()` would reduce allocation.

### #14 `findLast`/`find` with `subList` creates temporary lists (lines 970-971, 1017-1018)

Inside the candidate sorting comparator and per-candidate loop. For large hour lists, direct index iteration would be more efficient.

---

## Minor

### #15 `cachedPaints` is not thread-safe (line 127)

Widget rendering is single-threaded in practice, but worth a comment noting the assumption.

### #16 Magic numbers scattered as literals

dp values like `19.5f`, `15.5f`, `16f`, `3.2f`, `42f` appear inline. Named constants would make them adjustable and self-documenting.

---

## Summary

| Severity | Count | Key Areas |
|----------|-------|-----------|
| Bug | 4 total, 2 fixed | println in prod (fixed), paint mutation (fixed), dead branches (open) |
| Architecture | 4 | Method length, god object, parameter counts, role strings |
| Duplication | 3 | Age label, fetchY, suppression blocks |
| Performance | 3 | Path allocation, PathMeasure in loop, subList |
| Minor | 2 | Thread safety, magic numbers |

## Recommended Priority

1. **#8 role enum** — highest payoff for long-term maintainability
2. **#11 deduplicate suppression blocks** — reduces bug surface area
3. **#5 extract label placement phases** — enables targeted unit testing
4. **#10 extract tempToY helper** — quick win, eliminates copy-paste risk
