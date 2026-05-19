# Plan: Implement TemperatureGraphRenderer Code Review Findings

## Goal
Address all 10 code review findings in TemperatureGraphRenderer.kt to improve maintainability, reduce duplication, and fix minor issues.

## Scope Decisions (from user)
- **Finding #2/#10** (extract methods): Include — refactor `placeTemperatureLabels` and `tryExactFitForDirection`
- **Finding #3** (logging): Keep raw `Log.d()` — label placement logs are useful in release builds. **No change.**
- **Finding #6** (fetch-dot dedup): Yes, merge `computeFetchDotBounds` + `drawFetchDot`

## Active Findings (8 of 10)

| # | Finding | Risk | Phase |
|---|---------|------|-------|
| 1 | Deduplicate role sets (`LOGGED_ROLES`) | Low | 1 |
| 4 | Replace NaN sentinel in `CurveIntrusion` | Low | 1 |
| 5 | Hoist `dpToPx` out of step loops | Low | 1 |
| 9 | Missing blank line formatting | Low | 1 |
| 6 | Merge `computeFetchDotBounds` + `drawFetchDot` | Medium | 2 |
| 10 | Split `tryExactFitForDirection` into check + draw | Medium | 3 |
| 2 | Extract per-candidate placement from `placeTemperatureLabels` | Medium-High | 3 |
| 8 | Return `drawnIconBounds` instead of mutating param | Low | 4 |
| 7 | Comment-only: horizontal bounds note for `resolveStalenessInitialLayout` | Trivial | 4 |

## Phases

### Phase 1: Low-risk constants & formatting (Findings #1, #4, #5, #9)
Status: pending

Small, isolated changes with no behavioral impact.

**#1 — Replace `shouldLogPlacement` disjunction with set membership:**
- Lines 48-52: Replace 7-clause `||` chain with `private val LOGGED_ROLES = setOf(...)` + `role in LOGGED_ROLES`
- Same semantics, single source of truth for logged roles

**#4 — Replace NaN sentinel in `CurveIntrusion`:**
- Lines 72-123
- `CurveIntrusion.NONE` → `CurveIntrusion(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)`
- `isEmpty` → `minY > maxY`
- `curveIntrusionInLabel`: initialize `minY = Float.POSITIVE_INFINITY`, `maxY = Float.NEGATIVE_INFINITY`, drop NaN checks (lines 93-94, 120-123)
- `merge` works unchanged with infinities

**#5 — Hoist `dpToPx` out of step loops:**
- `placeTemperatureLabels` lines 444-448: compute `gapAbovePx`/`gapBelowPx` once before the `for (step...)` loop
- `tryExactFitForDirection` line 621: already computed once at function start — verify no redundant calls inside loops

**#9 — Add missing blank line:**
- Line 130-131: blank line between `combinedCurveIntrusion` closing brace and `STALENESS_MINOR_OVERLAP_RATIO`

**Verification:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraph*"`

---

### Phase 2: Fetch-dot pipeline dedup (Finding #6)
Status: pending

Merge `computeFetchDotBounds` (lines 902-939) and `drawFetchDot` (lines 941-1012) to eliminate ~40 lines of duplicated intermediate computation.

**Current state:**
- Both methods compute identical intermediates: `clampedX`, `fetchY`, `dotRadius`, `valueLabel`, `valueWidth`, `sideGap`, `aboveGap`, `baselineOffset`, `vAscent`, `vDescent`, `ageMinutes`, `ageLabel`, `sAscent`, `sDescent`, `ageWidth`, `padding`
- `computeFetchDotBounds` returns bounds only (no drawing)
- `drawFetchDot` draws + returns bounds

**Approach:**
1. Create a private data class:
   ```kotlin
   private data class FetchDotLayout(
       val clampedX: Float,
       val fetchY: Float,
       val dotRadius: Float,
       val outerRadius: Float,
       val valueLabel: String,
       val valueLayout: ValueLabelLayout?,
       val ageLabel: String?,
       val stalenessLayout: StalenessInitialLayout?,
   )
   ```
2. Extract `resolveFetchDotLayout(ctx, hours): FetchDotLayout?` that computes all layout decisions. Returns null if preconditions not met.
3. Replace `computeFetchDotBounds` with: call `resolveFetchDotLayout`, build `List<RectF>` from layout fields
4. Replace `drawFetchDot` with: call `resolveFetchDotLayout`, draw from layout, return drawn bounds
5. The staleness displacement loop (lines 991-997) stays in `drawFetchDot` since it depends on `ctx.drawnLabelBounds` which changes between pre-registration and drawing

**Key test classes:** `TemperatureGraphRendererFetchDotTest`, `TemperatureGraphRendererStalenessTest`

**Verification:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraphRendererFetchDotTest" --tests "com.weatherwidget.widget.TemperatureGraphRendererStalenessTest"`

---

### Phase 3: Extract label placement helpers (Findings #10, #2)
Status: pending

This is the highest-risk phase. Proceed carefully with a test run between each extraction.

**Step A — Finding #10: Split `tryExactFitForDirection` (lines 607-708)**

Extract a `checkExactFitBlockers(...)` function:
```kotlin
private sealed class ExactFitBlockerResult {
    object NaturalFits : ExactFitBlockerResult()
    object LabelOrIconBlocked : ExactFitBlockerResult()
    data class CurveOnly(val intrusion: CurveIntrusion, val baseBounds: RectF, val baseGapPx: Float) : ExactFitBlockerResult()
}
```

- Lines 620-653 (check phase) → `checkExactFitBlockers`
- Lines 654-707 (displacement + draw phase) → stays in `tryExactFitForDirection`
- `tryExactFitForDirection` calls check first, then either returns or proceeds to draw
- `GAVE_UP` stays local to the draw phase

**Step B — Finding #2: Extract `placeSingleLabel` from `placeTemperatureLabels`**

The outer loop body (lines 408-557) is self-contained per candidate:
- Reads: `ctx`, `hours`, `drawnIconBounds`, `gapDp`, `labelAscent`, `labelDescent`
- Mutates: `drawnLabelMetas` (append-only)
- Writes to canvas

Extract:
```kotlin
private fun placeSingleLabel(
    ctx: RenderContext,
    candidate: TempLabelCandidate,
    hours: List<HourData>,
    drawnLabelMetas: MutableList<PlacedLabelMeta>,
    drawnIconBounds: List<RectF>,
    gapDp: GraphLabelPlacementUtils.LabelGapDp,
    labelAscent: Float,
    labelDescent: Float,
)
```

`placeTemperatureLabels` becomes:
```kotlin
private fun placeTemperatureLabels(ctx, hours, drawnIconBounds, numColumns) {
    val extrema = ...
    val specialCandidates = ...
    val gapDp = ...
    val labelAscent = ...
    val labelDescent = ...
    TemperatureLabelResolver.sortLabelCandidates(specialCandidates)
    for (candidate in specialCandidates) {
        placeSingleLabel(ctx, candidate, hours, drawnLabelMetas, drawnIconBounds, gapDp, labelAscent, labelDescent)
    }
    ctx.drawnLabelBounds.addAll(drawnLabelMetas.map { it.bounds })
}
```

**Risk mitigation:** Run full renderer test suite between Step A and Step B.

**Key test classes:** `TemperatureGraphLabelPlacementRobolectricTest`, `TemperatureGraphRendererLabelPlacementTest`, `TemperatureGraphClutterRoboTest`, `TemperatureGraphLabelGeneralRoboTest`

**Verification:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraph*"`

---

### Phase 4: Side-effect cleanup & documentation (Findings #8, #7)
Status: pending

**#8 — Return `drawnIconBounds` from `drawHourLabelsAndIcons`:**
- Change signature from `(ctx, hours, drawnIconBounds: MutableList<RectF>)` to `(ctx, hours): List<RectF>`
- Return the list instead of mutating a parameter
- Update caller in `renderGraph` (lines 1085-1086):
  ```kotlin
  val drawnIconBounds = drawHourLabelsAndIcons(ctx, hours)
  ```

**#7 — Add horizontal-bounds comment to `resolveStalenessInitialLayout`:**
- Line 873: Add comment explaining that horizontal bounds aren't checked because `clampedX` is already screen-clamped by the caller

**Verification:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraph*"`

---

### Phase 5: Final verification
Status: pending

1. Run full test suite: `./gradlew test`
2. Verify all tests pass
3. No behavioral changes expected — all phases are pure refactoring

## File Inventory

| File | Phase | Changes |
|------|-------|---------|
| `TemperatureGraphRenderer.kt` | 1-4 | All 8 active findings |

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
|       |         |             |
