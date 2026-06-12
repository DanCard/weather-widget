# Fix: forecast extreme labels draw long leader lines when nested under a taller actual curve

## Context

On the Android (emulator) hourly temperature graph, forecast daily-high labels get long vertical
**leader lines**: "84°" (Wed forecast high), "91°" (Thu forecast high), plus the "63°" forecast
valley and "67.6°". The user: "Long leader lines for 84 and 91 daily high for forecast aren't
helpful."

### Root cause (log-confirmed)

These forecast peaks are nested *under* a much taller ACTUAL (pink) curve. Curve-avoidance pushes
the forecast label up to clear the towering actual curve → `reason=above+curveFit(16.5px)`,
`displacementSteps=1`, a long leader. Logs: `HIGH idx=358 (91°)` with ACTUAL_HIGH at idx 356
(97.7°), 2 indices away; `LOCAL idx=90 (84°)` with ACTUAL_HIGH at idx 97 (93.1°), 7 indices away.

My earlier desktop fix `computeCoincidentForecastExemptIndices` only matched the **exact same index**,
so at Android's ~7× point density (the coincident actual extreme lands 2-7 indices away) the
exemption misses and the leader returns. This is a resolution-dependent special case.

### Intended outcome

A forecast extreme nested under a taller/deeper actual curve sits flush on its **own forecast** peak
or valley with no leader line, on both platforms, regardless of index distance.

## Approach

Generalize the existing `ACTUAL_LOW` carve-out: **forecast-series labels avoid only the forecast
curve, never the actual curve.** This is resolution-independent (no index window) and subsumes the
exact-index exemption. The actual observed line (different color) is no longer treated as an obstacle
for forecast labels; label-vs-label avoidance, fetch-dot hard bounds, and `placeActualHighAboveCurve`
all stay enforced.

## Changes — `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

1. **Generalize `avoidanceActualPoints` (~line 270).** Add a private
   `FORECAST_ONLY_AVOIDANCE_ROLES = setOf(HIGH, LOW, LOCAL, FORECAST_HIGH, FORECAST_LOW,
   PAST_FORECAST_HIGH, PAST_FORECAST_LOW)` and set
   `avoidanceActualPoints = if (role == ACTUAL_LOW || role in FORECAST_ONLY_AVOIDANCE_ROLES) emptyList() else actualVisiblePoints`.
   This value already flows into `tryExactFitCurveAvoidance` and the main-loop
   `combinedCurveIntrusion`, so no other call sites change. Update the adjacent comment to the
   generalized rule. Keep `actualVisiblePoints` for START / END / ACTUAL_END (their below/edge
   behavior is forecast-curve- or actual-anchored and separately tested).

2. **Remove the now-redundant exact-index exemption.** Delete `computeCoincidentForecastExemptIndices`
   (~line 131-150), its call site (~line 188), drop `coincidentForecastExempt` from
   `isCurveAvoidanceExempt` (→ `idx in leftEdgeOrder` only), and remove the engine-local
   `FORECAST_HIGH_ROLES`/`FORECAST_LOW_ROLES` (~line 117-122). **Do not touch** the resolver's own
   `FORECAST_*_ROLES` or `addCoincidentActuals` — the coincident ACTUAL injection (which creates the
   taller-actual neighbor label) must stay.
   - A forecast HIGH at its own peak has its forecast curve below it → no above-intrusion → places
     flush, no leader. This subsumes the same-index case the helper handled.

## Tests

- **Primary regression gate**: `TemperatureCoincidentForecastInnerSideTest` (asserts forecast HIGH/LOW
  flush, no leader) must stay green — now satisfied via forecast-only avoidance instead of the deleted
  helper. (Forecast peak's forecast curve is below the above-box → NaturalFits → flush; ACTUAL_HIGH
  still goes above via `placeActualHighAboveCurve`, so `actualHigh.y < forecastHigh.y` holds.)
- **Add** (new class e.g. `TemperatureForecastNestedUnderActualNoLeaderTest`, reuse the
  `runEngineTest` harness): forecast HIGH nested under a taller actual high at a **different nearby
  index** ⇒ `placedAbove==true`, `drawLeaderLine==false`, `displacementSteps==0`; a forecast LOCAL
  peak likewise; and a forecast LOW nested above a deeper actual low ⇒ `placedAbove==false`, no leader
  (covers the 63° valley).
- **Unaffected** (verified by the design): `TemperatureLabelCollisionOrderTest` (label-vs-label flip),
  `TemperatureActualLowOwnCurveGrazeTest` (ACTUAL_LOW already empty), `TemperatureValleyBelowCascade`,
  `TemperatureLeftEdgeStartOrder`, `GraphLabelPlacementUtilsTest`, and the Robolectric suite
  (fetch-dot/END/ordering cases rely on forecast-curve or hard-bound logic, all retained).

## Verification

1. `./gradlew :shared:test` — all green, especially `TemperatureCoincidentForecastInnerSideTest` and
   the new nested-no-leader tests.
2. `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraphLabelPlacement*"` — no regressions.
3. `./gradlew installDebug`; screenshot the emulator hourly graph (`adb -s emulator-5554 exec-out
   screencap`): 84° / 91° (and 63° / 67.6°) sit flush with no leader lines. Confirm via logcat that
   their `LabelPlacementDebug` shows `displacementSteps=0` and no `curveFit`.
4. `scripts/buildStart.sh` (desktop) — the desktop 91° still sits flush.

## Notes
- Scope is the one role predicate at line 270 + removing the subsumed helper; START/END/ACTUAL_END,
  left-edge ordering, hard-bound (fetch-dot) avoidance, label ordering, and `placeActualHighAboveCurve`
  are untouched.
- Memory: [[coincident_forecast_inner_side]] (will be updated — exact-index helper replaced by the
  general forecast-only-avoidance rule), [[actual_low_label_and_ordering]],
  [[hourly_label_pipeline_index_keyed]].
