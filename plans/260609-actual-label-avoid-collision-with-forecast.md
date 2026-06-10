# Fix: ACTUAL_LOW label avoids forecast curve by trying above on collision

## Context

On Samsung, the ACTUAL_LOW label (e.g. 62°) partially collides with the forecast (dashed white) curve. This happens when the forecast curve dips below the observed actual valley — the forecast line runs through the below-label space and the engine places the label there anyway (with an allowed visual dip).

User preference: ACTUAL_LOW prefers below placement. But if there is a curve collision below, it should try above instead.

## Root Cause

`tryExactFitCurveAvoidance` in `TemperatureLabelEngine.kt` iterates `directions = [false, true]` for a valley label (below first). When the forecast curve occupies the below-space:

- `checkExactFitBlockers` returns `CurveOnly`
- `tryExactFitForDirection` computes an extra gap to push the label further down, then places it with up to `allowedDipPx + 1f` (≈16px at 3× density) residual curve overlap → `PLACED`
- Because `PLACED` returns `true` immediately, the normal loop never runs, and the label ends up visually overlapping the forecast curve

The GAVE_UP path (shallow intrusion ≤ allowed dip) already falls through to the normal loop correctly, where below-curve-collision → cascade returns None → tries above → placed above. Only the deep-intrusion PLACED path needs fixing.

## Fix

**File:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

In `tryExactFitForDirection` (~line 482), inside the `is ExactFitBlockerResult.CurveOnly` branch, add an early return for ACTUAL_LOW's below direction:

```kotlin
is ExactFitBlockerResult.CurveOnly -> {
    if (candidate.role == TemperatureRole.ACTUAL_LOW && !placeAbove) {
        return ExactFitOutcome.LABEL_OR_ICON_BLOCKED
    }
    val extra = if (placeAbove) { ...
```

**Why `LABEL_OR_ICON_BLOCKED`:** Returning this makes `tryExactFitCurveAvoidance` `continue` to the above direction. If above is `NaturalFits`, it returns `false` (falls to normal loop). In the normal loop, below has `overlapsCurve = true` → valley cascade returns `None` (no label collision) → above direction at step 0 → placed above cleanly.

**Why this is safe:** The actual curve at the ACTUAL_LOW valley is at `sy` (minimum temp = max Y). Below-placement bounds start at `sy + gapBelowPx > sy`. The actual curve can only be at Y ≤ sy, so it never intrudes into the below-bounds — any `CurveOnly` intrusion for ACTUAL_LOW's below placement is from the forecast curve.

## Trace for each case after fix

| Scenario | Curve avoidance result | Normal loop | Final placement |
|---|---|---|---|
| No curve below | NaturalFits → return false | below clear → placed below | below ✓ |
| Deep forecast curve below | LABEL_OR_ICON_BLOCKED → try above | below overlapsCurve → above clear → placed above | above ✓ |
| Shallow forecast curve below | LABEL_OR_ICON_BLOCKED → try above | below overlapsCurve → above clear → placed above | above ✓ |
| Label blocker + curve below | LabelOrIconBlocked (label takes priority) → try above | (same path) | above ✓ |
| Curve both above and below | below BLOCKED → above CurveOnly → pushed above | — | above (adjusted) ✓ |

## Verification

```bash
# Full unit suite — no regressions expected
./gradlew testLongDebugUnitTestFresh
```

Then build and check Samsung visually: the ACTUAL_LOW label should appear above the actual temperature line when the forecast curve is in the below-space.
