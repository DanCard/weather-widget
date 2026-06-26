# Fix: drop the redundant left-edge START label when neighbors crowd it

## Issue

In the 3-day hourly temperature graph (observed on the emulator), the **left edge stacked three
labels** vertically: forecast daily HIGH (`75°`), the per-day ACTUAL_HIGH (`72.7°`), and the forecast
**START** boundary label (`73°`). The `73°` START was redundant noise — it sits within 0.3° of the
per-day actual high right beside it — and should be dropped when other labels are already there.

## Root cause

Verified from the app's own `TempLabelResolver`/`TempLabelEngine` logcat output. The three surviving
labels were HIGH (idx 28, x≈1.8–38.8px), ACTUAL_HIGH (idx 30), and START (idx 0, x≈0–37px). START and
HIGH occupy nearly the **same horizontal strip** yet are **28–30 indices apart**: this is a 663-point
view whose observed region near the left edge is sub-hourly (idx 0→28 spans ~30 min ≈ ~5px).

Two gaps in `checkRedundantPairSuppression` (`shared/.../graph/TemperatureLabelResolver.kt`) combined
to let START survive:

1. **Index window too small in the dense region.** The boundary redundancy window came from
   `computeRedundantPairWindow`, which derives a single *averaged* px-per-hour and clamps to
   `REDUNDANT_PAIR_WINDOW_CAP = 8` indices (≈7px here). It cannot represent that idx 0 and idx 30 are
   ~5px apart — index distance is a poor proxy for screen distance under non-uniform sampling.
2. **Boundary roles only checked GLOBAL extrema.** For START/END the redundancy check compared only
   against `extrema.actualHighIndex` (the *global* actual high — here the 74° peak far to the right at
   idx 401), never the pixel-adjacent **per-day** actual high (72.7°, idx 30) in
   `extrema.actualDailyHighIndices`. And vs the forecast HIGH (75°) the value diff is exactly 2, which
   is not `< 2`.

The `checkLeftEdgeSuppression` boundary exemption (START/END always allowed at idx 0) plus the
engine's place-below fallback then guaranteed START was drawn.

## Fix

All changes in `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
(shared code → fixes Android and desktop):

1. **Added `pixelGapByTime(hours, idxA, idxB, widthPx)`** — the on-screen horizontal gap (px) between
   two indices derived from their real timestamps, not index position. Returns `Float.MAX_VALUE` when
   geometry is unknown (`widthPx<=0`) or the span is degenerate, so geometry-less unit tests fall back.
2. **Split `START`/`END` out of the `LOCAL`/`ACTUAL_END` redundancy branch.** For the graph edges,
   "nearby" is now a `REDUNDANT_PAIR_PX = 64f` pixel budget via `pixelGapByTime` (falling back to the
   legacy index window only when `widthPx<=0`), and the redundancy targets now include the **per-day**
   actual extrema (`actualDailyHighIndices` + `actualDailyLowIndices`) in addition to the global
   extrema. The `2°` value threshold is unchanged.
3. **Threaded `hours` and `widthPx`** into `checkRedundantPairSuppression` and its call site.
4. Left `checkLeftEdgeSuppression` and the START+actual pairing (`computeLeftEdgeStartOrdering`)
   untouched — suppression happens earlier in `collectLabelCandidates`, so a suppressed START simply
   never reaches the pairing/engine.

Result: START (73°) is now redundant against the per-day ACTUAL_HIGH (72.7°) — ~5px gap, 0.3° value
diff — and is suppressed, leaving the informative `75°` and `72.7°`.

### Why the intended pairing still survives

`TemperatureLeftEdgeStartOrderTest` pairs START=64° with a nearby actual=66.9° — a **2.9°** difference,
above the `2f` threshold — so the new check does not suppress it. The value gate cleanly separates
"genuinely different boundary value worth pairing" from "near-duplicate redundant edge label."

## Tests

Added two regression tests in `TemperatureLabelSuppressionTest.kt` via a `denseLeftEdgeHours` helper
that builds a 31-sample one-minute observed cluster + an hourly remainder (faithfully reproducing the
index-far / pixel-near geometry, with the global actual high on the next day):
- `left-edge START is dropped when a per-day actual high is pixel-near but index-far` (peak 72.7° → 1.7°
  from START → suppressed).
- `left-edge START is retained when the nearby per-day actual high differs by more than 2 degrees`
  (peak 74° → 3° from START → kept). A first attempt with peak 68° revealed a data flaw — the sparse
  day-1 reading (71°) became day 1's per-day high and coincided with START — fixed by raising the
  control peak so the cluster stays day 1's high.

## Verification

- `./gradlew :shared:test --tests "com.weatherwidget.shared.graph.*"` — full shared graph package green,
  including the two existing left-edge/pairing tests and the two new regression tests.
- User verified the fix on-device: the left edge now shows only `75°` and `72.7°`, no `73°`.

Memory note saved: `left_edge_start_pixel_redundancy` (index-vs-pixel + global-vs-per-day trap).
