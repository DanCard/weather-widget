# Fix: forecast LOW label drawn on top of fetch-dot value label (Samsung + desktop)

## Context

On the hourly temperature graph, at the valley near NOW, the **forecast LOW** temperature
label is drawn directly on top of the **fetch-dot's actual-temperature value label**, producing
garbled overlapping text:

- **Samsung**: orange forecast LOW "63°" overlaps pink fetch-dot value "61°" → renders "631°"
- **Desktop**: white forecast LOW "62°" overlaps pink fetch-dot value "61.2°"

The user wants the forecast LOW label drawn **above the curve line** ("on top of the curve"),
cleanly separated from the pink fetch-dot value label that sits at/below the dot.

### Confirmed root cause (live logcat `LabelPlacementDebug` + screenshots)

The pink number is **not** a placed `ACTUAL_LOW` label — the real `ACTUAL_LOW` is *suppressed*
(`LabelSuppressed reason=FETCH_DOT`, `TemperatureLabelResolver.kt:182/595-598`) because the dot
already shows that observed value. The pink number is the **fetch-dot value label**. The forecast
LOW (role `LOW`) at the adjacent hour is then placed `reason=below, displacementSteps=0` — the
engine found *no collision* and dropped it below, right where the fetch-dot label sits.

Why the engine misses the collision — obstacles reach `TemperatureLabelEngine.computePlacements`
only via the `drawnIconBounds` parameter (plus its internal `drawnLabelMetas`):

- **Android** (`TemperatureGraphRenderer.kt`): `render()` computes `computeFetchDotBounds(...)`
  (which *does* include the value-label rect, `:651-660`) and adds it to `ctx.drawnLabelBounds`
  (`:799-800`) — but `placeTemperatureLabels` only forwards the hour-icon bounds into the engine
  (`:415-432`). The fetch-dot value label is **never passed in**. The engine is blind to it.
- **Desktop** (`TemperatureGraph.kt`): the value rect *is* fed in via `drawnIconBounds`
  (`:415` → `:523`), but for a valley LOW placed below, the engine allows **minor icon overlap**
  (`MINOR_OVERLAP_ICON_RATIO`, engine `:283/286`, `:562-564`), so the white LOW grazes the pink
  label instead of flipping above.

The shared `tryValleyBelowCascade` FlipAbove path (`:750-757`) can't help: it only fires when the
colliding obstacle is a *placed* label in `drawnLabelMetas` with `isValleyBelow=true`. The fetch-dot
value label is an external obstacle, never in `drawnLabelMetas`.

### Intended outcome

The fetch-dot value/age labels become **hard obstacles** (no minor-overlap tolerance) so a forecast
LOW colliding with them flips **above the curve**, reusing the engine's existing above-placement /
leader-line / clamp machinery. Fix lives in `:shared` so both platforms converge.

## Approach

Add a dedicated `reservedHardBounds: List<GraphRect> = emptyList()` parameter to
`computePlacements`, checked as a hard collision in exactly the three placement gates — never run
through `shouldAllowMinorOverlap` / `isMinorOverlapEligible` / `MINOR_OVERLAP_ICON_RATIO`. The
default-empty keeps every existing caller and test byte-identical.

Rejected alternatives: (b) making valley LOW treat *all* icon overlap as non-minor regresses tuned
icon behavior; (c) a proximity/“opposite-side” special-case re-introduces fragile geometric
special-casing and duplicates the value-label position logic the renderers already compute.

## Changes

### 1. Shared engine — `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

- **Signature**: add `reservedHardBounds: List<GraphRect> = emptyList()` to `computePlacements`
  (after `drawnIconBounds`).
- **Main displacement loop** (~`:277-293`): add `overlapsHard = reservedHardBounds.any { it.intersects(bounds) }`
  and fold into `hasCollision` with no minor-overlap allowance. With `directions = listOf(false, true)`
  for valleys, a hard below-collision falls through to the `true` (above) branch automatically.
- **Curve-avoidance** `checkExactFitBlockers` (~`:555-576`) and the post-displacement re-check in
  `tryExactFitForDirection` (~`:638-643`): thread `reservedHardBounds` down through
  `tryExactFitCurveAvoidance` → `tryExactFitForDirection` → `checkExactFitBlockers`; treat a hit on
  `baseBounds`/`newBounds` as an unconditional blocker so a below candidate returns
  `LABEL_OR_ICON_BLOCKED` and the above direction is tried.
- **Valley cascade** `tryValleyBelowCascade` (~`:691-779`): pass `reservedHardBounds` in; treat as a
  hard obstacle in the shift/relax candidate checks (`:721-723`) so the below-escape hatches don't
  slide the LOW back under the pink label. `collidingMeta` flip logic unchanged.

### 2. Android — `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

- `placeTemperatureLabels`: add a `fetchDotBounds: List<RectF>` param; in the engine call pass
  `reservedHardBounds = fetchDotBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }`.
- `render()` (`:801`): pass the already-computed `fetchDotPreBounds` (from `computeFetchDotBounds`,
  `:799`) into `placeTemperatureLabels(...)`. These bounds already include dot ring + value label +
  age label rects — all correct to treat as hard.

### 3. Desktop — `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt`

- Add `val fetchDotHardBounds = mutableListOf<Rect>()`; when the value rect (`:415`) and
  `finalAgeRect` (`:455`) are added to `drawnLabels`, also add them to `fetchDotHardBounds`.
- Keep `drawnIconBounds = drawnLabels.map {...}` as-is; add
  `reservedHardBounds = fetchDotHardBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }`
  to the `computePlacements` call.

## Regression safety

`reservedHardBounds` defaults empty, so all existing callers/tests are unchanged. Hard bounds are
external rects never added to `drawnLabelMetas`, so the valley-vs-valley cascade, left-edge
START/actual ordering, and `placeActualHighAboveCurve` (returns before any obstacle logic) are
untouched. Staleness age-label placement is unchanged — the pre-pass age rect fed as a hard bound is
the same rect temperature labels already partially avoided via `ctx.drawnLabelBounds`.

## Tests

- **New shared test** `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelFetchDotHardBoundsTest.kt`
  (copy the `runEngineTest`/`TestLabelTextMetrics` scaffold from `TemperatureValleyBelowCascadeTest.kt`):
  1. Forecast LOW adjacent to a `reservedHardBounds` rect placed at its below-slot ⇒ assert
     `placedAbove == true`; control without the param ⇒ `placedAbove == false` (reproduces bug).
  2. Hard bound far from the LOW ⇒ `placedAbove == false` (below preserved).
  3. Empty-default invariance for a representative valley.
  4. Low classified as `LOW` vs `FORECAST_LOW` ⇒ both flip above.
  5. Compressed `heightPx` ⇒ exercises leader-line/clamp; assert no residual intersection with the
     hard bound (LOW/FORECAST_LOW are essential, so worst case is a leader line, never overlap).
  6. Peak-side mirror (forecast HIGH near a dot value label drawn above the dot) ⇒ displaces away.
- **Robolectric** extend `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`:
  full render with fetch dot at a valley + adjacent forecast LOW of a nearby-but-different value
  (not suppressed). Via the `onLabelPlaced` debug callback assert the LOW's `placedAbove == true`
  and its y is above the fetch-dot value y. This guards the Android call-site wiring (a pure
  shared-engine test cannot catch a missing argument).

## Verification

1. `./gradlew :shared:test --tests "*TemperatureLabelFetchDotHardBounds*"` — new test red before the
   engine change (control), green after.
2. `./gradlew testDebugUnitTest --tests "*TemperatureGraphLabelPlacement*"` and full
   `./gradlew :desktop:test` — no regressions.
3. Android device: `./gradlew installDebug`, trigger a widget redraw, screenshot the valley
   (`adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`) and confirm the
   forecast LOW (63°) sits above the curve, pink fetch-dot value (61°) below — no "631°".
   Confirm via `adb logcat | grep LabelPlacementDebug` that the LOW logs `placedAbove=true`.
4. Desktop: `scripts/buildStart.sh` to rebuild + relaunch; open the popup, confirm the white
   forecast LOW (62°) sits above the curve, separated from the pink 61.2° value label.

## Notes

- Suppression is unchanged: when the forecast low value rounds equal to the dot value (within 12dp)
  it stays geometry-suppressed (the dot already shows it). The bug only manifests when the values
  differ (63 vs 61, 62 vs 61.2) — the test must use differing values.
- Memory pointers: see `hourly_label_pipeline_index_keyed`, `actual_low_label_and_ordering`,
  `desktop_label_placement_divergence`, `renderer_test_color_is_zero`.
