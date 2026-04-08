# Task Plan: Implement CR #6 - Break down TemperatureGraphRenderer singleton (updated per explore + existing utils)

## Goal
Mechanically extract self-contained sections from TemperatureGraphRenderer.kt (~1602 LOC) into GraphRenderUtils.kt, GraphLabelPlacementUtils.kt, and targeted new files. Only move code + minimal delegation/imports/qualification (no optimizations, no logic changes). One extraction per phase, run relevant `*TemperatureGraphRenderer*` tests after each. Update planning files after every 2 actions. End with optimization recommendations.

## Current Phase
Phase 2: Extract Label/Overlap Helpers (in_progress)

## Phases
### Phase 1: Requirements & Discovery (COMPLETE)
- [x] Read notes + analyzed structure (grep for fun/data class, explored with Task agent)
- [x] Fixed broken TempLabelCandidate.kt + removed duplicate from main (compilation blocker from prior session)
- [x] Updated findings.md/progress.md; baseline tests pass
- **Status:** complete

### Phase 2: Extract Label/Overlap Helpers to GraphLabelPlacementUtils.kt (in_progress)
- [x] Moved MINOR_OVERLAP_HEIGHT_RATIO, isMinorOverlapEligible, shouldAllowMinorOverlap, maxVerticalOverlap + updated calls in placeTemperatureLabels + qualified TemperatureRole
- [ ] Run label-specific tests (`*LabelPlacement*` + `*SuppressionTest*`)
- [ ] Update test references if needed (mechanical)
- **Status:** in_progress

### Phase 3: Extract Paint + Color Utilities to GraphRenderUtils.kt
- [ ] Move COLOR_*, thresholds, tempToColor, blendColors, formatTemp, withAlpha, ensurePaints, PaintSet, buildTempGradient, dpToPx, tempToY
- [ ] Delegate calls in renderGraph/draw* methods
- [ ] Run full renderer tests
- **Status:** pending

### Phase 4: Extract Scaling/Layout + RenderContext
- [ ] Move computeScaling, computeLayout, Layout data class, RenderContext/Update + related to GraphRenderUtils or new GraphLayout.kt
- [ ] Update renderGraph orchestration (minimal)
- [ ] Run tests
- **Status:** pending

### Phase 5: Extract Core Path/Compute + Label Placement
- [ ] Move computePoints, buildAnchoredActualPoints, interpolateYAtX, placeTemperatureLabels/collectLabelCandidates/isRedundantNear/computeExtremaIndices to appropriate utils (addresses #5)
- [ ] Run label + actuals + continuity tests
- **Status:** pending

### Phase 6: Remaining (FetchDot, DayLabels, Icons, Extrema already partially in GraphRenderUtils)
- [ ] Clean up remaining duplication in main renderer; move draw* methods if self-contained
- [ ] Run all `*TemperatureGraphRenderer*` tests + lint/typecheck
- **Status:** pending

### Phase 7: Integration & Verification (pending)
- [ ] Verify main renderGraph() is now thin orchestrator
- [ ] `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraphRenderer*"` (expect all pass)
- **Status:** pending

### Phase 8: Recommendations & Close
- [ ] Document optimizations (#3-5, #7, #9-16) in findings.md (no implementation)
- [ ] Update progress.md with per-step test results
- **Status:** pending

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Leverage existing GraphRenderUtils.kt + GraphLabelPlacementUtils.kt | Mechanical (add methods vs new files where logical); aligns with prior extractions (smoothing, drawDayLabels, drawFetchDot already there) |
| Qualified names for moved items (e.g. GraphLabelPlacementUtils.isMinorOverlapEligible) | No test breakage; minimal change per "moving code around" rule |
| Fix prior extraction bugs first | Evidence-first (compilation failure in TempLabelCandidate.kt blocked tests) |
| Update plan after every phase + 2 actions | Per planning-with-files skill (read before decide, write findings after discovery) |

## Errors Encountered
| Error | Resolution |
|-------|------------|
| TempLabelCandidate.kt syntax + unresolved TemperatureRole + duplicate class | Fixed syntax, removed duplicate from main (step 1); tests now compile/pass |
| Test task command | Used `:app:testDebugUnitTest --tests "*..."` (root `test` doesn't support --tests directly) |

**Re-read before next action. Next: Complete Phase 2 by moving overlap helpers, run tests, update this file.**
