# Plan: END/START labels honor curve avoidance + flip below when curve descends through them

## Context

On the emulator, the rightmost forecast endpoint label ("79°" in the user's screenshot) is drawn ON TOP of the dashed forecast curve. The curve peaks at 84° earlier in the day and descends back to 79° at the right edge; the descent passes through the upper part of the "79°" text. Empty space below the endpoint is clean. The user wants the END label drawn UNDER the forecast line in this case.

Root cause: `TemperatureRole.END` (and by symmetry `START`) is missing from two role-sets in `TemperatureGraphRenderer.kt`, so the label gets none of the curve-aware placement logic the other roles enjoy.

## Root cause (concrete)

In `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`:

1. **`CURVE_AVOIDANCE_ROLES`** (lines 37–44) = `{ACTUAL_END, ACTUAL_HIGH, ACTUAL_LOW, HIGH, LOW, LOCAL}`. **`END` and `START` are absent.**
   - Line 416 gate: `tryExactFitCurveAvoidance` is never invoked for END.
   - Line 463–464: `curveAvoidanceEligible=false`, so `overlapsCurve` is forced false — the main step-loop never treats the curve as a collision.
2. **`valueBasedRoles`** (line 406) = `{ACTUAL_END, LOCAL}`. END/START fall through to `!placement.isValley`, so they almost always prefer above — geometrically blind.
3. **Logging filter** (lines 483, 492, 524, 542 and parallel filters in `tryValleyBelowCascade` at 749, 761, 772) omits `END`/`START`, which is why `adb logcat | grep LabelPlacementDebug` shows nothing for the 79° label.

The midpoint label added in commit `1a2e635` uses `TemperatureRole.LOCAL` (via `resolveExtremaRole` at `TemperatureLabelResolver.kt:195` for any non-extrema, non-edge index). `LOCAL` is **already** in both `CURVE_AVOIDANCE_ROLES` and `valueBasedRoles`, so midpoints already get correct treatment — we just verify, no code change needed.

## Why `prefersAbovePlacement` fixes the 79° case

`prefersAbovePlacement(candidate)` at lines 49–62 inspects ±5 neighbor values and returns `true` only when `(nearMax - v) < SIGNIFICANT_MAX_GAP (= 1.0f)`.

- 79° endpoint with 84° peak within ±5: `nearMax=84`, diff `5.0 ≥ 1.0` → returns **false** → `directions = [below, above]`.
- 77° START with descending curve to ~53°: `nearMax≈77`, diff `<1.0` → returns **true** → `directions = [above, below]` (preserves today's good behavior).
- Endpoint that's a local high (ascending into it): `nearMax≈v` → returns **true** → above. Same as today.

So flipping END/START into `valueBasedRoles` is geometry-aware and keeps the currently-correct cases correct.

## Changes

### 1. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

a. **Add END and START to `CURVE_AVOIDANCE_ROLES`** (lines 37–44):
```kotlin
private val CURVE_AVOIDANCE_ROLES: Set<TemperatureRole> = setOf(
    TemperatureRole.ACTUAL_END,
    TemperatureRole.ACTUAL_HIGH,
    TemperatureRole.ACTUAL_LOW,
    TemperatureRole.HIGH,
    TemperatureRole.LOW,
    TemperatureRole.LOCAL,
    TemperatureRole.START,
    TemperatureRole.END,
)
```

b. **Add END/START to `valueBasedRoles`** at line 406:
```kotlin
val valueBasedRoles = candidate.role == TemperatureRole.ACTUAL_END ||
    candidate.role == TemperatureRole.LOCAL ||
    candidate.role == TemperatureRole.START ||
    candidate.role == TemperatureRole.END
```

c. **Extend the logging filter helper.** Lines 483, 492, 524, 542, 749, 761, 772 each repeat the same 6-role check. Extract a small private helper rather than duplicating yet again:
```kotlin
private fun shouldLogPlacement(role: TemperatureRole): Boolean =
    role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW ||
    role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH ||
    role == TemperatureRole.ACTUAL_END || role == TemperatureRole.LOCAL ||
    role == TemperatureRole.START || role == TemperatureRole.END
```
Replace the 7 inline disjunctions with `if (shouldLogPlacement(candidate.role))`. Keeps the diff focused, prevents the next role addition from missing a site.

### 2. No change to `TemperatureLabelResolver.kt`

`ESSENTIAL_LABEL_ROLES` already includes both `START` and `END` (lines 29–30), so the force-fallback path still kicks in if both directions truly fail. Because direction order now starts with `below` for the 79° case, the recorded `forceBounds` becomes the below-position — which is the desired fallback. No additional safeguard needed for this bug.

### 3. New test in `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`

Add one test asserting END places below when a higher forecast peak earlier in the window pushes the curve through the above-position. Pattern after the existing `end label uses forecast temperature after fetch transition not ghost line when endpoint is uncrowded` (line 244):
- Build hours: ascending to a clear peak, then descending into the endpoint (e.g., `[70, 75, 80, 84, 82, 79]` with the last few being forecast).
- Render, capture placements via `onLabelPlaced`.
- Assert: `placements.find { it.role == TemperatureRole.END }!!.placedAbove == false`.
- Optionally assert `reason` starts with `"below"`.

Don't remove or alter the existing END tests — they check value/series/colorFamily, not direction, so they continue to pass.

## Critical files

- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — the three edits above
- `app/src/main/java/com/weatherwidget/widget/TemperatureLabelResolver.kt` — read-only verify (ESSENTIAL_LABEL_ROLES already has END/START)
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt` — new test case
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererLabelPlacementTest.kt` — sanity-check no regression (currently asserts minor-overlap eligibility for END/START, not affected)

## Verification

1. **Unit + Robolectric tests:**
   ```
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraphRendererLabelPlacementTest"
   ```
2. **Install on emulator and visually verify the 79° case:**
   ```
   ./gradlew installDebug
   adb -s emulator-5554 logcat -c
   # Force a widget refresh (tap the widget or rotate device)
   adb -s emulator-5554 exec-out screencap -p > /tmp/widget_after.png \
     && convert /tmp/widget_after.png /tmp/widget_after.jpg
   ```
   Open `/tmp/widget_after.jpg`. The 79° label should be drawn BELOW the curve endpoint, with no part of the dashed forecast line passing through the text.
3. **Confirm via logcat** (now that END is in the logging filter):
   ```
   adb -s emulator-5554 logcat -d | grep "TempGraphRenderer.*role=END"
   ```
   Expect: `LabelPlacementDebug` line with `role=END`, `placedAbove=false`, `reason=below` (or `below+curveFit(...)` if curve avoidance pushed it further).
4. **Regression check on START:** With the same data, the 77° START label should remain ABOVE (curve descends away from start; `prefersAbovePlacement` returns true). Verify in screenshot and logcat.
