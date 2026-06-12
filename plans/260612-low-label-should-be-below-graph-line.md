# Fix: ACTUAL_LOW label flips above its own valley when its own actual line grazes the below-box

## Context

Desktop hourly temperature graph, Thursday valley: the **"60.6°" ACTUAL_LOW** label is drawn
*above* the pink actual line (with a short leader line into the cramped space between curves), when
it should sit **below** the valley in the clear open space — like a normal low label, and like the
clean "60.8°" fetch-dot value on the adjacent valley.

### Root cause (log- and visually-confirmed)

`TemperatureLabelEngine.kt` has a rule (in `tryExactFitForDirection`, `CurveOnly` branch, ~line 663)
that blocks ACTUAL_LOW from placing **below** whenever a curve intrudes the below-box, forcing it
above. The original 260609 plan justified this by assuming *"any CurveOnly intrusion for ACTUAL_LOW's
below placement is from the forecast curve"* (the actual curve can only be at `Y ≤ sy`).

That assumption is **false**: the observed line has sub-hourly points (and rendered smoothing) that
dip a few px **below** the labeled hourly minimum. `combinedCurveIntrusion(actualVisiblePoints,
forecastPoints, …)` merges *both* series, so the ACTUAL_LOW's **own** pink line grazes its below-box
(logs: `intrusion minY=562.4 maxY=565.1`, anchor `560.2`, a ~3px graze well within `allowedDip`).
The forecast curve is far away here, yet the rule still flips the label above. User confirmed: "the
forecast line is far away. I don't see an intrusion."

### Intended outcome

ACTUAL_LOW avoids only the **forecast** curve, never its own actual line (it labels that line, so
grazing it is expected — same principle as the existing left-edge and coincident-pair
curve-avoidance exemptions). Forecast far → placed below (Thu case). Forecast genuinely dips below
the valley → still flips above (the original Samsung case, preserved).

## Approach

For ACTUAL_LOW candidates, exclude the actual (own) curve from the curve-avoidance intrusion
computations — pass an empty list in place of `actualVisiblePoints` to the intrusion checks so only
`forecastPoints` is considered. Keep the existing ACTUAL_LOW below-block as-is; with the actual
curve excluded it now fires *only* on real forecast intrusion, matching the documented intent.

## Changes — `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

In the per-candidate loop (after `geometry` is resolved, near `preferAbove`), compute once:

```kotlin
// ACTUAL_LOW labels the actual valley; its own (sub-hourly/smoothed) line dipping a few px below the
// anchor must not be treated as an obstacle. Avoid only the forecast curve for it.
val avoidanceActualPoints = if (candidate.role == TemperatureRole.ACTUAL_LOW) emptyList() else actualVisiblePoints
```

Then replace `actualVisiblePoints` with `avoidanceActualPoints` at the two avoidance entry points
(and only those — `placeActualHighAboveCurve` and other non-avoidance uses keep the full list):

1. **Main displacement loop** (~line 329): the `combinedCurveIntrusion(actualVisiblePoints,
   forecastPoints, bounds)` call → use `avoidanceActualPoints`.
2. **`tryExactFitCurveAvoidance(...)` call** (~line 271): pass `actualVisiblePoints =
   avoidanceActualPoints`. It threads down to `checkExactFitBlockers` and the residual
   `combinedCurveIntrusion` re-check inside `tryExactFitForDirection`, so both exact-fit intrusion
   computations become forecast-only for ACTUAL_LOW. No signature changes needed.

### Resulting traces (ACTUAL_LOW below)
| Scenario | forecast-only intrusion in below-box | Result |
|---|---|---|
| Forecast far (Thu 60.6°) | empty → NaturalFits / no main-loop curve collision | **placed below**, no leader ✓ |
| Forecast dips below valley (Samsung) | non-empty → CurveOnly → ACTUAL_LOW block → above | placed above ✓ (preserved) |

## Tests

- **Preserve**: `TemperatureLabelCollisionOrderTest` (ACTUAL_LOW-below + forecast-low-above pairs)
  and `TemperatureValleyBelowCascadeTest` must stay green.
- **Add** (`shared/src/test/kotlin/.../graph/`): a case where the ACTUAL_LOW's own actual curve dips
  a few px below its anchor while the forecast curve sits well above → assert the ACTUAL_LOW is
  `placedAbove == false` (and no leader). Reproducing the sub-anchor actual graze in the
  hours-based harness is awkward, so build `computePlacements` inputs directly: `forecastPoints`
  well above the valley, and an `actualVisiblePoints`/`originalPoints` list whose segment near the
  ACTUAL_LOW index dips just below the anchor. Control (without the fix) flips above; with the fix
  it stays below. Also add a forecast-dips-below variant asserting it still flips above.

## Verification

1. `./gradlew :shared:test` — new test green, `TemperatureLabelCollisionOrderTest` /
   `TemperatureValleyBelowCascadeTest` unchanged.
2. `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraphLabelPlacement*"` — no regressions.
3. Rebuild + restart desktop (`scripts/build-start.sh`), open popup (`touch
   ~/.local/share/weather-widget/.show`), screenshot the Thursday valley: the "60.6°" ACTUAL_LOW now
   sits below the pink valley with no leader line. Confirm via the desktop log that
   `ACTUAL_LOW idx=… placeAbove=false` and `intrusion=none`.

## Notes
- Scope strictly to `ACTUAL_LOW` (the reported role). ACTUAL_HIGH uses `placeActualHighAboveCurve`
  (separate path, above its own peak); ACTUAL_END not reported — leave both unchanged.
- Memory: [[actual_low_label_and_ordering]], [[hourly_label_pipeline_index_keyed]],
  [[coincident_forecast_inner_side]], [[fetch_dot_value_label_hard_bound]].
