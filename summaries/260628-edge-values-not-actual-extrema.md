# Hourly graph: edge values are not actual high/low

## Problem

Two reports of cluttered/overlapping pink actual labels on the hourly temperature graph:

- **Pixel 7 Pro** (left edge): a pink actual low `58.9°` stacked on the amber forecast low
  `58°`, both at `idx=0`. The `idx=0` sample is the left edge of a zoomed window — the real
  overnight low is off-screen; it's just where the window was cut at 8am.
- **emulator-5554** (interior + right edge): near a 4a–8a valley, a nonsensical `51.9°`
  ACTUAL_HIGH was drawn right beside a `51.5°` ACTUAL_LOW. The day's true high (`59°`) was at
  the right edge (NOW); after excluding it, the code substituted a tiny interior shoulder bump
  (`51.9°`) as the "high."

Root cause: `TemperatureExtrema` deliberately treated the first/last observed sample as a
boundary extremum (old `actual_low_left_edge_label` / `boundary_high_drop_left_edge`
behavior). A bare window edge is not a confirmed peak/valley.

## Rule (set by user)

Pink actual high/low labels are emitted **only at genuine interior turning points** (a
peak/valley with an observed neighbour on both sides). Applies to **both** edges, including
the right edge = NOW, for highs and lows. Additionally: **"an extreme is not an extreme if the
edge is more extreme"** — when a day's most-extreme sample sits at an edge, do NOT fall back to
a lesser interior point; the day gets no label on that side.

## Change

All in `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`
(`compute()`), the single shared engine Android `TemperatureGraphRenderer.kt` and desktop
`TemperatureGraph.kt` both delegate to:

1. `isActualLocalMin`/`isActualLocalMax` require a real neighbour on both sides
   (`if (i <= actualStartIndex || i >= actualEndIndex) return false`), removing the old
   symmetric edge auto-exemption.
2. Per-day selection kept as **absolute max/min THEN `filter { isActualLocalMax/Min }`** (not
   filter-then-max). With #1 this drops an edge absolute-extreme with no interior substitute —
   the "edge more extreme ⇒ no extreme" clause for free. (An interim draft wrongly inverted
   this to filter-then-max and produced the emulator `51.9°` substitution; reverted.)
3. Removed the now-dead `boundaryHighDrops` block + its `BOUNDARY_HIGH_DROPPED` log (its whole
   premise was the edge exemption).
4. Updated the stale boundary-exemption comments.

No change needed in `TemperatureLabelResolver`: once an edge leaves
`actualDailyLow/HighIndices`, `addCoincidentActuals` stops injecting and `resolveExtremaRole`
falls through to the forecast role (e.g. left edge shows only the amber forecast `LOW`).

## Tests

Updated to the new behavior; all `:shared`, `:app`, `:desktop` suites green:

- `TemperatureExtremaIncompleteDayTest.kt` — edge low/high not labeled; interior bump cooler
  than the edge high is NOT substituted; new monotonic-rising-window → no extrema test.
- `TemperatureLabelSuppressionTest.kt` — left/right/fetch-dot edge lows are no longer
  ACTUAL_LOW (END/forecast value shown instead); the FORECAST_HIGH-redundancy test moved its
  ACTUAL_HIGH to an interior peak.
- `TemperatureLabelCollisionOrderTest.kt`, `TemperatureValleyBelowCascadeTest.kt`,
  `TemperatureGraphLabelPlacementRobolectricTest.kt` — lone isolated observed points (which
  only counted as extrema via the old edge exemption) converted to 3-point interior
  valleys/peaks so they still exercise placement logic.

## Verification

Unit: `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` — all pass. On-device:
user verified on Pixel 7 Pro and emulator — left-edge `58.9°` clutter gone (only amber `58°`),
emulator `51.9°` bogus high gone (only `51.5°` low remains), interior peaks/valleys still
labeled.

## Notable trade-off

Choosing "both edges" means the current/latest observed extreme at NOW is no longer labeled as
a high/low on the graph (current temp is still shown in the header). This intentionally
reverses the prior right-edge/NOW labeling features.

## Memory

New: `actual_extrema_edges_not_extrema`. Superseded/annotated obsolete:
`actual_low_left_edge_label`, `boundary_high_drop_left_edge`.
