# Hourly graph: edge values must not be classified as actual high/low

## Context

On the Pixel 7 Pro hourly temperature graph (zoomed-in day view), two labels overlap
heavily at the **bottom-left**: a pink **actual low** `58.9°` stacked on the amber
**forecast low** `58°`. Live device logs confirm both sit at `idx=0`:

```
LabelAccepted: displayed="58"   role=LOW       idx=0   (amber forecast, dailyLowIndex)
LabelAccepted: displayed="58.9" role=ACTUAL_LOW idx=0  reason=COINCIDENT_WITH_FORECAST_LOW
```

The pink `ACTUAL_LOW` is injected by `addCoincidentActuals(...)` because its text
("58.9") differs from the forecast's ("58"), even though the two are within ~1°F.

Root cause (user's framing): **`idx=0` is just the left edge of the visible window, not a
real valley.** The genuine overnight low is off-screen to the left; the window was simply
cut at 8am. Today's extrema classifier *deliberately* treats the first/last observed
sample as a boundary extremum (the `actual_low_left_edge_label` /
`boundary_high_drop_left_edge` behavior). That assumption is wrong: an actual high/low
should only be a **true turning point** (a peak/valley with observed neighbours on both
sides). Bare edge values are just edge values.

Intended outcome: at the left edge the pink `58.9°` disappears; only the amber forecast
`58°` remains (it is `dailyLowIndex`, a forecast extreme, handled separately). Genuine
interior peaks/valleys keep their pink labels. This reverses the two prior boundary-edge
decisions noted above — intentional, per explicit user direction.

### Refinement (after Pixel verification): "edge more extreme ⇒ no substitute"

User decision: the rule applies to **both** edges (left and right/NOW), for highs and lows.

A second clutter case appeared on emulator-5554 (zoomed 4a–8a window): the curve cools to
a real valley (51.5° interior) then warms to its true high **59° at the right edge**. The
edge high was correctly dropped — but an earlier draft also tried to *substitute* the
warmest **interior** turning point, a tiny shoulder bump **51.9°**, labeling it ACTUAL_HIGH
right next to the 51.5° low. The user's rule: **an extreme is not an extreme if the edge is
more extreme** — i.e. when a day's most-extreme sample sits at an edge, do NOT fall back to
a lesser interior point; the day simply gets no label on that side.

This is achieved by selecting each day's **absolute** extreme and then requiring it to be an
interior turning point (so an edge absolute-extreme is dropped with no substitution). That
is the ORIGINAL selection logic combined with the edge-gated predicates — see change #2.

## Change

All in **`shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`**
(`compute()`), the single shared engine both Android `TemperatureGraphRenderer.kt` and
desktop `TemperatureGraph.kt` delegate to.

1. **Require a real neighbour on both sides** in the turning-point predicates
   (`isActualLocalMin` / `isActualLocalMax`, ~lines 100-111). Remove the left/right edge
   auto-exemption:

   ```kotlin
   fun isActualLocalMin(i: Int): Boolean {
       // A genuine valley needs an observed neighbour on BOTH sides confirming the turn.
       // The first/last observed sample is just a window edge (the real turning point may
       // lie off-screen), so it is never an actual extreme. Only true peaks/valleys are.
       if (actualStartIndex < 0 || i <= actualStartIndex || i >= actualEndIndex) return false
       return actualLabelTemps[i] <= actualLabelTemps[i - 1] &&
              actualLabelTemps[i] <= actualLabelTemps[i + 1]
   }
   // isActualLocalMax: same shape with >=.
   ```
   Bounds are safe: `i` is strictly inside `(actualStartIndex, actualEndIndex)`.

2. **Keep the ORIGINAL per-day selection** (~lines 131-132): pick each day's *absolute*
   max/min, THEN require it to be an interior turning point. Combined with the edge-gated
   predicates (#1), an edge absolute-extreme is dropped with NO interior substitution —
   exactly the "edge more extreme ⇒ no extreme" rule. (An earlier draft inverted this to
   "pick from turning points," which wrongly substituted a lesser interior shoulder — the
   emulator-5554 51.9° clutter. Revert it back to:)

   ```kotlin
   val rawDailyHighIndices = actualByDay.values
       .mapNotNull { d -> d.maxByOrNull { actualLabelTemps[it] } }
       .filter { isActualLocalMax(it) && dayHighReached(it) }.sorted()
   val perDayLowIndices = actualByDay.values
       .mapNotNull { d -> d.minByOrNull { actualLabelTemps[it] } }
       .filter { isActualLocalMin(it) }.sorted()
   ```

3. **Remove the now-dead `boundaryHighDrops` block** (~lines 215-233) and set
   `actualDailyHighIndices = shoulderedHighIndices.filterNot { it in /* nothing */ }` →
   just `shoulderedHighIndices`. Its entire premise (the edge exemption auto-truing
   `leftOk` at `actualStartIndex`) no longer holds, so it can never fire. Drop its
   `BOUNDARY_HIGH_DROPPED` log too.

4. **Update the stale comments** at ~lines 88-98 (the "BOTH ends exempt symmetrically /
   coldest at midnight in 24h day view" rationale) and ~lines 215-224 to describe the new
   turning-point-only rule.

Leave untouched: global `actualHighIndex`/`actualLowIndex` (lines 81-82; only used as
redundancy targets / logging, not as anchors), `interPeakLows`, `shoulderDrops`,
`degenerateLowDrops` — all operate on interior points and remain valid.

No change is needed in `TemperatureLabelResolver.kt`: once `idx=0` leaves
`actualDailyLowIndices`, `addCoincidentActuals` no longer injects the pink label and
`resolveExtremaRole(0)` falls through to the existing forecast `LOW`.

## Tests to update — `TemperatureExtremaIncompleteDayTest.kt`

These two currently assert the OLD (now-reversed) behavior and must be rewritten:

- `coldest-at-edge boundary low is never dropped` — `ascendingBoundaryLowPoints` has its
  coldest value (50°) at `idx=0`. New expectation: `0 !in actualDailyLowIndices` (edge,
  not a valley). Rename/retarget to assert edges are no longer labeled.
- `separated left-edge boundary high...` — `separatedBoundaryHighPoints` has its day-max
  (70°) at `idx=0` (edge) and a LESSER interior bump (68°) at `idx=2`. Per the "edge more
  extreme ⇒ no substitute" rule, the day gets NO high. New expectation:
  `0 !in actualDailyHighIndices` AND `2 !in actualDailyHighIndices`. (NOTE: an earlier draft
  of this test asserted `2 in ...`; that must be flipped to `2 !in ...`.)

Also: the collision/cascade tests (`TemperatureLabelCollisionOrderTest`,
`TemperatureValleyBelowCascadeTest`) used a single isolated observed point that only counted
as an extreme via the old edge exemption; convert each to a 3-point interior valley/peak
(neighbours on both sides) so they still exercise the placement logic. (Done.)

Still-passing (interior turning points, verified by reading): `incomplete current day's
morning max...` (idx1/idx5), `current day's observed max IS the high...` (idx5),
`edge day whose...render identically...` (idx1/idx2), `descending boundary-start sliver
high is dropped` (idx0 dropped, idx2/idx4 kept), plus `TemperatureLeftEdgeStartOrderTest`
(ACTUAL_LOW at idx2), `TemperatureLeftEdgeHighOrderTest` (ACTUAL_HIGH at idx1), and
`TemperatureActualLowOwnCurveGrazeTest` (valley at idx6).

Add one new regression test: a monotonic-rising observed window (overnight low off-screen)
yields NO `actualDailyLowIndices` at `idx=0`, mirroring the Pixel 7 Pro case.

## Verification

1. Unit tests (fast, plain JUnit):
   - `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.*"`
   - `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraphLabelPlacement*"`
   Fix any edge-asserting failures per the section above.
2. On-device: `./gradlew installDebug`, then trigger a redraw of the existing Pixel 7 Pro
   widget (`adb -t 3 shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE` or
   tap to refresh), capture `adb -t 3 exec-out screencap -p > p.png && convert p.png p.jpg`
   and confirm the bottom-left now shows only the amber `58°` (no stacked pink `58.9°`),
   while interior peaks/valleys still carry pink labels.
3. Sanity-check a wider (multi-day history) view to confirm interior per-day highs/lows are
   still labeled and only literal first/last samples lost their labels.

## Follow-up

After implementation, update memory: `actual_low_left_edge_label` and
`boundary_high_drop_left_edge` are reversed — edges are no longer actual extrema; only
turning points are. Add a short note that the per-day extreme is now chosen from turning
points.
