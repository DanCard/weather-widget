# Code Review — Graph Label Placement Engine (`shared/graph/`)

Date: 2026-08-12
Scope: `shared/src/main/kotlin/com/weatherwidget/shared/graph/` (38 files, ~6.2k lines) plus the two
platform call sites (`TemperatureGraphAnnotationRenderer`, `desktop/TemperatureGraph`) and the log
routing (`shared/util/Log.kt`, `util/AndroidLogSink.kt`, `AppModule.dbLogger` wiring).

## 1. Overall Assessment

The engine is the strongest-architected part of the codebase and a model of the project's
"pure-function extraction" strategy:

1. Every placement decision is platform-free; Android Canvas and desktop Compose only *draw* the
   returned `PlacedLabel`/`Placement` lists.
2. Documentation is exceptional — each object carries a KDoc that explains not just *what* but
   *why* (e.g. `TodayColumnOverlayPlanner` retells the 260806 placement-rewrite failure).
3. Test coverage is heavy and behavior-shaped: 38 test files (~7.1k lines), each named for a real
   past bug (`TemperatureLabelFetchDotHardBoundsTest`, `TemperatureValleyBelowCascadeTest`,
   `ActualExtremeLabelStackingTest`, …).
4. No TODOs/FIXMEs; the "evidence-first" logging culture (VERBOSE breadcrumbs with provenance)
   is present throughout.

The problems are **not** in the *ideas* — they are in the *structure of two files* and in the
**replication of the collision test**, which is the subsystem's most safety-critical rule.

## 2. Findings (by severity)

### HIGH

**H1 — The collision rule is implemented (at least) three times, with drift.**

"Does this candidate box collide?" is the single most important decision in the engine, and its full
definition — label minor-overlap budget, icon minor-overlap ratio, hard-bound side-only test, curve
intrusion tolerance — is spelled out independently in three places:

1. the main step loop in `TemperatureLabelEngine.computePlacements` (~lines 400–470),
2. `checkExactFitBlockers` / `tryExactFitForDirection` (the curve-avoidance pre-pass, ~lines 640–760),
3. the forced placers `placeActualHighAboveCurve` / `placeActualLowBelowCurve` via
   `overlapIsWithinBudget`.

The code even documents this ("the same minor-overlap budget the main placement loop applies",
"same yield rule … mirrored downward"). The three copies have already drifted:

- **The forced placers never test hard bounds (`reservedHardBounds`) or icon bounds at all.** They
  only run `overlapIsWithinBudget`, which checks *placed labels* against the minor-overlap budget.
  `placeActualHighAboveCurve` and `placeActualLowBelowCurve` receive no `reservedHardBounds` or
  `drawnIconBounds` argument. So an ACTUAL_HIGH whose text differs from the fetch-dot value (the
  `resolveCandidateGeometry` drop only fires on *equal text* within 12dp) can be drawn across the
  fetch-dot's pink value/age label with no detection. This is exactly the class of bug
  (`reservedHardBounds` was added for "631° garble" in a prior plan) that the hard-bound test exists
  to prevent — it just doesn't apply on the forced path.
- The exact-fit pre-pass and the main loop both check hard bounds, but the forced placers re-apply a
  *subset* of the rule.

**Recommendation:** extract one `CollisionTester` that, given `(bounds, role, drawnLabelMetas,
drawnIconBounds, reservedHardBounds, labelHeight)`, returns a single verdict plus the per-obstacle
breakdown for logging. Make all three passes call it. This is the highest-value change in this
review and directly de-risks future label bugs.

**H2 — Curve-intrusion geometry is duplicated three times with different semantics.**

1. `curveIntrusionInLabel` + `combinedCurveIntrusion` in `TemperatureLabelEngine` — precise,
   per-segment interpolation, returns `CurveIntrusion(minY, maxY)` (intrusion extent).
2. `GraphEmptySpaceFinder.curveClearance` — fixed 5-sample, returns signed *clearance*.
3. `GhostLineLabel.curveClearance` — fixed 5-sample, returns signed clearance with a graze
   tolerance, null on deep penetration.

Three implementations of "does the curve cross this rect". The engine's version is strictly the most
precise (it interpolates each segment, so sub-hourly observed curves are handled correctly), while
the other two sample at 5 points — a coarser approximation that is fine for the free-floating labels
but not obviously equivalent. They should share one geometric primitive (`CurveIntrusion`/segment
intersection), with the sampling density as a parameter; at minimum `CurveIntrusion` + its two
functions should live in their own file so the primitive is isolated and testable on its own.

### MEDIUM

**M1 — `TemperatureLabelEngine.computePlacements` is a ~430-line god function.**

It declares ~15 mutable locals up front (`placed`, `forceBaselineY`, `forceBounds`, `forceX`,
`forceDrawBelow`, `forceStep`, `flipDecided`), uses a labelled `outer@` loop with `break@outer` and
`continue`, and interleaves geometry resolution, left-edge ordering, two forced-placer branches,
the curve-avoidance pre-pass, the step loop, the valley cascade, the force fallback, and logging.
This is the least-readable function in the subsystem. It should be reduced to a per-candidate
orchestrator that delegates to the (already-separate) sub-strategies.

**M2 — `TemperatureLabelResolver.collectLabelCandidates` is a ~230-line pipeline.**

It threads a mutable `suppressedIndices` through four suppression passes and then appends
actual/coincident/midpoint candidates. The individual checks are already functions; the loop should
be extracted as named stages (`applySuppressionPasses`, `enrichWithCoincidentAndMidpoints`) so the
pipeline reads top-to-bottom instead of as one 230-line body.

**M3 — `checkRedundantPairSuppression` (~110 lines) is the densest logic in the resolver.**

The START/END branch alone carries three different target lists (daily, secondary-forecast, actual),
two different thresholds, an inline `runBounds` same-run rule, and two nested lambdas (`isTarget`,
`nearEnough`). Decompose into `isBoundaryRedundant(role, idx, …)` helpers keyed by target class.

**M4 — `deduplicateAnchors` uses `Triple<String, Int, Int>` as a slot key.**

The triple is (formatted value, run-first, run-last). A one-line data class `ValueSlot` is
self-documenting and removes the `.first/.second/.third` positional confusion.

**M5 — `filterDenseLabelCandidates`'s threshold semantics are implicit in magnitude.**

The `threshold > 5` test distinguishes "decluttering" (≤5) from "reduction" (>5) thresholds, and
`effectiveThresholds` prepends a `5` only when `firstOrNull() > 5`. This works for the two current
callers (temperature `[3,4,5]`; value labels from `HourlyGraphDefaults`) but silently changes
behavior for any future caller that passes e.g. `[6,7,8]`. The declutter/reduce distinction should
be an explicit parameter, not inferred from numeric magnitude.

**M6 — `sortLabelCandidates`'s comparator does O(n) list scans per comparison.**

`findPrevDifferent`/`findNextDifferent` walk the full `labelTemps` on every comparator invocation
(O(n² log n)), and a comparator that walks collections is a code smell. Negligible at current
window sizes (≤ ~72 points) but a latent hazard and a readability trap.

**M7 — `findLocalExtremaIndices` is implemented twice.**

`GraphLabelPlacementUtils.findLocalExtremaIndices(List<Int>)` and
`TemperatureExtrema.findLocalExtremaIndices(List<Float>)` are near-duplicate plateau-extrema
algorithms with subtly different plateau handling. Unify on one generic.

### LOW

**L1 — Per-render breadcrumbs use `Log.d` in the engine but `Log.v` in the resolver.**

`TemperatureLabelResolver` deliberately emits its per-render label decisions at `Log.v` (the comment
explains why: VERBOSE is ephemeral). But `TemperatureLabelEngine` emits `EngineInput`,
`ExactFitOutcome`, `ExactFitPreCheck`, `CurveAdjust`, `LabelCascade` at `Log.d` — same per-render
frequency, different tier. On Android these are default-visible in logcat on every widget render,
against the project's own "high-frequency → VERBOSE" convention. (Not an `app_logs` issue — the
shared `Log` sinks to logcat/console only — but a convention violation and logcat-noise issue.)

**L2 — `prefersAbovePlacement` magic numbers (`VALUE_NEIGHBOR_WINDOW = 5`, `SIGNIFICANT_MAX_GAP =
1.0f`) have only a terse comment.** A sentence on why 5 neighbours and 1° would help future readers.

**L3 — FORCED vs forced-placer asymmetry.** In the main loop an "essential" label that cannot be
placed is still emitted with `reason = "FORCED"` (never drops), while `placeActualHighAboveCurve`
/ `placeActualLowBelowCurve` *drop* the label when there is no room. The asymmetry is deliberate
(essential labels are a floor; a near-coincident second ACTUAL_HIGH is redundant) but deserves an
explicit doc comment at the `geometry.isEssential` fallback site, since it is easy to misread as a
bug.

## 3. Split Proposal (concrete)

The two god files should be decomposed into single-responsibility files, each keeping its KDoc and
becoming `internal` (public API surface unchanged: `computePlacements`, `PlacedLabel`, `formatTemp`
stay where the callers expect them).

### 3.1 `TemperatureLabelEngine.kt` (1202 → orchestration + extracted)

| New file | Contents |
|----------|----------|
| `CurveIntrusion.kt` | `CurveIntrusion` data class, `curveIntrusionInLabel`, `combinedCurveIntrusion`, segment-interpolation primitive (feeds H2) |
| `CollisionTester.kt` | the unified collision verdict from H1 (label/icon/hard/curve + minor-overlap budgets) |
| `ActualExtremePlacers.kt` | `placeActualHighAboveCurve` + `placeActualLowBelowCurve`, unified into one parameterized placer; takes `reservedHardBounds`/`drawnIconBounds` so H1 is fixed |
| `CurveFitPlacer.kt` | `tryExactFitCurveAvoidance` + `checkExactFitBlockers` + `tryExactFitForDirection` + `ExactFitOutcome` + `ExactFitBlockerResult` |
| `ValleyCascade.kt` | `tryValleyBelowCascade` + `ValleyCascadeOutcome` + `CascadeResult` |
| `TemperatureLabelEngine.kt` (kept) | `PlacedLabel`, `computePlacements` (reduced to per-candidate orchestration), constants |

### 3.2 `TemperatureLabelResolver.kt` (1031 → facade + extracted)

| New file | Contents |
|----------|----------|
| `LabelCandidateCollector.kt` | `collectLabelCandidates` + `mostExtremeTurn`, `addActualTurningPointLabels`, `addForecastMidpointLabel`, `addCoincidentActuals`, `resolveExtremaRole`, `buildPotentialAnchors`, `deduplicateAnchors`, role sets |
| `LabelSuppression.kt` | the four `check*Suppression` functions + `isRedundantNear` + `SuppressionResult` |
| `LabelGeometryResolver.kt` | `resolveCandidateGeometry` + `centerOfRun` + `sortLabelCandidates` + `findPrevDifferent`/`findNextDifferent` + `formatTemp` + `runBounds`/`anchorMinutes`/`pixelGapByTime` |
| `TemperatureLabelResolver.kt` (kept) | `ResolvedLabelGeometry`, `ESSENTIAL_LABEL_ROLES`, `computeExtremaIndices`, facade delegating to the above |

## 4. Verification Plan

1. `./gradlew :shared:test` — the ~513-test suite runs in ~1s and is the primary safety net; all
   graph tests must stay green after extraction.
2. `./gradlew :desktop:test` — desktop graph tests exercise the same call sites.
3. `./gradlew :app:testDebugUnitTest` — Robolectric label-placement tests (label engines are called
   from the Android renderer with real metrics).
4. Emulator screenshot before/after (daily + hourly + zoomed) to confirm pixel-identical output —
   the engine's whole contract is that a refactor must not change placement, only structure.
