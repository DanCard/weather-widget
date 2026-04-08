# Task Plan: Implement CR #6 - Break down 1573-line TemperatureGraphRenderer singleton

## Goal
Mechanically extract self-contained sections (scaling/layout, path computation, gradient, label placement, day labels, fetch dot, icon, extrema) from TemperatureGraphRenderer.kt into separate files/classes without optimizing, changing logic, or refactoring beyond moving code and minimal delegation. Do one extraction per step, run relevant tests after each, verify no breakage. At end, provide optimization recommendations.

## Current Phase
Phase 1: Discovery & Initial Setup

## Phases
### Phase 1: Requirements & Discovery (COMPLETE)
- [x] Read notes/260408-code-review-temperature-graph-renderer.md #6
- [x] Analyze current file structure using tools (functions, data classes)
- [x] Identify mechanical extraction targets: utilities, scaling, paths/curves, labels, fetch dots, extrema
- [x] Document findings.md with list of extractable sections
- [x] Create/update planning files
- **Status:** complete

### Phase 2: Extract Temperature Utilities
- [ ] Move color blending, tempToColor, formatTemp, withAlpha, tempToY, dpToPx to new TemperatureUtils.kt as object
- [ ] Update main renderer to delegate to new utils (minimal changes)
- [ ] Run unit tests for renderer
- **Status:** pending

### Phase 3: Extract Scaling and Layout
- [ ] Move computeScaling, computeLayout, RenderContext related to new GraphLayout.kt
- [ ] Update calls mechanically
- [ ] Run tests
- **Status:** pending

### Phase 4: Extract Path and Curve Logic
- [ ] Move computePoints, buildAnchoredActualPoints, buildSmoothCurve..., drawFillAndCurves, interpolateYAtX to new GraphPathBuilder.kt
- [ ] Delegate from main
- [ ] Run tests
- **Status:** pending

### Phase 5: Extract Label Placement
- [ ] Move placeTemperatureLabels, collectLabelCandidates, isRedundantNear, computeExtremaIndices and related (isMinorOverlap*, shouldAllow*) to new TemperatureLabelPlacer.kt (addresses #5 too)
- [ ] Run label-specific tests
- **Status:** pending

### Phase 6: Extract Fetch Dot, Day Labels, Icons, Extrema
- [ ] Move remaining: placeDayLabels, computeFetchDotBounds, drawFetchDot, findLocalExtremaIndices, drawHourLabelsAndIcons to appropriate classes
- [ ] Run all tests
- **Status:** pending

### Phase 7: Integration & Verification
- [ ] Ensure main renderGraph() orchestrates the extracted components
- [ ] Run full test suite: ./gradlew test --tests "*TemperatureGraphRenderer*"
- [ ] Update progress.md with test results per step
- **Status:** pending

### Phase 8: Recommendations
- [ ] After mechanical extraction, suggest optimizations/cleanup (no implementation unless asked)
- [ ] Update findings.md
- **Status:** pending

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Mechanical extraction only | Follow user instruction: move code, no optimization/changes except delegation |
| One component per phase + test run | Ensures incremental verification as requested |
| Use new objects/classes in same package | Allows easy delegation with minimal code change |
| Prioritize label placement | Overlaps with #5, high value per review |
| Use existing test classes | Run specific test classes after each extraction to verify |

## Errors Encountered
| Error | Resolution |
|-------|------------|
| None yet | |

**Note:** Update this file after each phase. Re-read before starting next phase. All changes must compile and pass tests before proceeding.
