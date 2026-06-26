# Fix: hourly-graph actual-high label stacks on forecast-high label (left edge)

## Context

On the hourly temperature graph (zoomed "5p–9p" view), at the top‑LEFT edge a pink
**actual** high label `73.7°` is drawn directly on top of the **forecast** high label
`74°`, hiding it. The user wants the pink actual label dropped **below the curve** so
both are legible.

Confirmed via logcat + screenshot — these are **two separate extrema at adjacent
indices**, not the same-index coincident path:

- Forecast `HIGH` role, `"74"`, **idx 0**, x≈0 (left edge), placed above.
- `ACTUAL_HIGH` role, `"73.7"`, **idx 1**, x=29, `placedAbove=true reason=aboveActualCurve`.

Root cause: in `TemperatureLabelEngine.computePlacements()`, `ACTUAL_HIGH` is
**unconditionally** force-placed above the curve via `placeActualHighAboveCurve(...)`
then `continue`s (`TemperatureLabelEngine.kt:229`). It never enters the collision loop
and never tests `drawnLabelMetas`, so it stacks on the already-placed forecast `HIGH`.
The existing `computeLeftEdgeStartOrdering` (`:115`) — which already encodes the exact
"warmer above, cooler below at the left edge" rule the user wants — only fires when the
forecast partner is a `START` role, so it does nothing here (partner is `HIGH`).

Intended outcome: the cooler observed high drops below its own peak; the warmer forecast
high stays above; both labels readable. Fix lives in the shared engine, so Android and
desktop both benefit (no renderer changes).

## Approach (reuse the existing left-edge ordering pattern)

Add a tightly-scoped **sibling** to `computeLeftEdgeStartOrdering` that pairs a left-edge
forecast `HIGH` with a near-coincident, **equal-or-cooler** `ACTUAL_HIGH`, emits a single
override forcing the actual below, and let that actual flow through the normal placement
loop. The forecast HIGH already defaults above and is placed correctly — leave it
untouched (emit only the actual index).

### Changes — `shared/.../graph/TemperatureLabelEngine.kt`

1. **New role set + helper** next to `computeLeftEdgeStartOrdering` (after line 125),
   reusing the existing `LEFT_EDGE_START_WINDOW` (8) and `TemperatureLabelResolver.formatTemp`:

   ```kotlin
   private val LEFT_EDGE_FORECAST_HIGH_ROLES = setOf(
       TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH,
   )

   // Left-edge counterpart to computeLeftEdgeStartOrdering for when the forecast partner is a
   // HIGH (not a START). A left-edge forecast high and a near-coincident equal-or-cooler
   // ACTUAL_HIGH would both be force-placed above and stack; drop the cooler observed high BELOW
   // its own peak (warmer forecast stays above). Scoped to the left edge AND the equal-or-cooler
   // case, so the lone/ warmer actual high is untouched. Returns ONLY the actual override.
   private fun computeLeftEdgeHighOrdering(candidates: List<TempLabelCandidate>): Map<Int, Boolean> {
       val forecast = candidates.filter { it.role in LEFT_EDGE_FORECAST_HIGH_ROLES }
           .minByOrNull { it.index } ?: return emptyMap()
       if (forecast.index > LEFT_EDGE_START_WINDOW) return emptyMap()       // left edge only
       val actual = candidates.filter {
           it.role == TemperatureRole.ACTUAL_HIGH && it.index != forecast.index &&
               abs(it.index - forecast.index) <= LEFT_EDGE_START_WINDOW
       }.minByOrNull { abs(it.index - forecast.index) } ?: return emptyMap()
       val forecastVal = forecast.labelTemps[forecast.index]
       val actualVal = actual.labelTemps[actual.index]
       if (TemperatureLabelResolver.formatTemp(forecastVal) == TemperatureLabelResolver.formatTemp(actualVal)) return emptyMap()
       if (forecastVal < actualVal) return emptyMap()   // genuinely warmer actual keeps default above
       return mapOf(actual.index to false)
   }
   ```

2. **Merge into `leftEdgeOrder`** (line 176). The existing START pairing wins on any key
   collision:

   ```kotlin
   val leftEdgeOrder = computeLeftEdgeHighOrdering(candidates) + computeLeftEdgeStartOrdering(candidates)
   ```

3. **Guard the force-above branch** (line 229) so a rerouted actual flows through the
   normal loop:

   ```kotlin
   if (candidate.role == TemperatureRole.ACTUAL_HIGH && idx !in leftEdgeOrder) {
       placeActualHighAboveCurve( /* unchanged args */ )
       continue
   }
   ```

No other lines change — downstream is already correct for an `idx in leftEdgeOrder`
entry: `preferAbove=false` → `directions=[below, above]` (`:208`); `isCurveAvoidanceExempt`
→ skips `tryExactFitCurveAvoidance` and forces `overlapsCurve=false` so the label sits
flush under its own observed peak (`:249`, `:339`); `ACTUAL_HIGH` is a peak so the valley
cascade is skipped; the below slot is clear (forecast HIGH is above) → placed at step 0,
`drawLeaderLine=false`.

### Why this approach

Reuses the codebase's established left-edge value-ordering semantics and "flush against
its own line" rationale rather than inventing a generic collision-flip. It only fires for
the specific left-edge forecast-HIGH ↔ equal-or-cooler-ACTUAL_HIGH pair, so the lone
actual high, the warmer-actual case, and mid-graph highs all keep the current
`placeActualHighAboveCurve` behavior unchanged.

## Test — new `shared/.../graph/TemperatureLeftEdgeHighOrderTest.kt`

Copy the private `buildHours` / `TestLabelTextMetrics` / `runEngineTest` harness from
`TemperatureLeftEdgeStartOrderTest.kt` (the direct analog). Cases:

- **Bug repro:** forecast global max at idx 0 (`forecast[0]=74`, descending, no larger
  value anywhere so idx0 resolves to role `HIGH` not `START`) + observed max at idx 1
  (`actual[0]=73, actual[1]=73.7`, descending) with enough observed hours through
  `observedAt`. Assert `forecastHigh.placedAbove == true`, `actualHigh.placedAbove == false`,
  `actualHigh.baselineY > forecastHigh.baselineY`.
- **Negative control (scoping):** same geometry but actual high **warmer** (e.g.
  `actual[1]=75`) → assert `actualHigh.placedAbove == true` (helper returns empty).
- While writing, verify idx0 actually resolves to role `HIGH` (text "74"), not `START`;
  nudge the forecast shape if the resolver yields `START`.

## Verification

- `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureLeftEdgeHighOrderTest"`
- Regression: `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.*"`
  — keep `TemperatureLeftEdgeStartOrderTest`, `DualHighLabelTest`,
  `TemperatureLabelCollisionOrderTest`, `TemperatureCoincidentForecastInnerSideTest` green.
- Device: `./gradlew installDebug`, reproduce the zoomed 5p–9p hourly view on the
  emulator, confirm pink `73.7` sits below the curve with `74` visible above
  (screenshot → JPG per CLAUDE.md).

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt` (edit)
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLeftEdgeHighOrderTest.kt` (new)
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLeftEdgeStartOrderTest.kt` (harness source)
