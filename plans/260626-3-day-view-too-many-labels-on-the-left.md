# Drop the redundant left-edge START label when neighbors crowd it

## Context

In the 3-day temperature graph (observed on the emulator), the **left edge shows three
stacked labels**: `75°` (forecast daily HIGH), `72.7°` (per-day ACTUAL_HIGH), and `73°`
(the forecast **START** boundary label). The `73°` START is redundant noise — it sits
within 0.3° of the per-day actual high right next to it and within 2° of the forecast high,
adding nothing the other two labels don't already convey. The user wants the left-edge
label dropped when other labels are nearby.

### Verified root cause (from the app's own `TempLabelResolver`/`TempLabelEngine` logs)

The three surviving labels are:

| displayed | role         | idx | x-bounds (px) |
|-----------|--------------|-----|---------------|
| `75`      | HIGH         | 28  | (1.8, 38.8)   |
| `72.7`    | ACTUAL_HIGH  | 30  | (–, –)        |
| `73`      | START        | 0   | (0, 37)       |

START (idx 0) and HIGH (idx 28) occupy **almost the same horizontal strip** (~0–38 px) yet
are **28–30 indices apart**. This is a 3-day view with 663 densely-sampled points; the
observed region near the left edge is sub-hourly (idx 0→28 spans just ~30 min). All
index-window-based suppression therefore fails to see these labels are pixel-adjacent.

Two specific gaps in `checkRedundantPairSuppression` (`TemperatureLabelResolver.kt`) let
START survive:

1. **Index window too small in the dense region.** The boundary redundancy window comes
   from `computeRedundantPairWindow`, which derives a single *averaged* px-per-hour and
   then clamps to `REDUNDANT_PAIR_WINDOW_CAP = 8` indices (≈ 7 px in this view). It cannot
   represent that idx 0 and idx 30 are ~5 px apart.
2. **Wrong redundancy targets for boundary roles.** For `START`/`END`/`LOCAL`/`ACTUAL_END`
   the check only compares against *global* extrema indices (`dailyHighIndex=28`,
   `actualHighIndex=401`, …). The pixel-adjacent **per-day** actual high (`72.7°`, idx 30,
   in `extrema.actualDailyHighIndices`) is never a candidate — the global actual high is
   the 74° peak far to the right at idx 401. And vs the forecast HIGH (75°) the value diff
   is exactly 2, which is not `< 2`.

The `checkLeftEdgeSuppression` boundary exemption (START/END always allowed at idx 0) and
the engine's place-below fallback then guarantee START gets drawn.

## Approach

Make the **START/END boundary redundancy check pixel-aware** and let it also consider the
**per-day actual extrema** as redundancy targets. This is consistent with the existing
documented intent (`TemperatureLabelResolver.kt:32-36`: "two same-ish-valued labels read as
a redundant pair only when they sit close together ON SCREEN. Index distance is a poor
proxy…"). Per-pair pixel distance from real timestamps is strictly more correct than the
averaged-and-capped index window and naturally handles non-uniform sampling.

### Changes (all in `shared/.../graph/TemperatureLabelResolver.kt`)

1. **Add a per-pair pixel-distance helper** (reusing `REDUNDANT_PAIR_PX = 64f`):
   ```
   private fun pixelGapByTime(hours, idxA, idxB, widthPx): Float
   ```
   Compute `|minutes(t[idxA], t[idxB])| / totalSpanMinutes * widthPx` from
   `hours[idx].dateTime`. Return `Float.MAX_VALUE` (or fall back to a generous index
   heuristic) when `widthPx <= 0` or span `<= 0`, so geometry-less unit tests are unchanged.

2. **Extend `checkRedundantPairSuppression` for `START`/`END`** (the literal graph edges —
   keep `LOCAL`/`ACTUAL_END` on their existing path to bound blast radius). Thread `hours`
   and `widthPx` in. For START/END, mark redundant when a stronger nearby label is BOTH
   pixel-near (`pixelGapByTime(...) <= REDUNDANT_PAIR_PX`) AND value-near
   (`< redundantValueThreshold = 2f`), checking these targets:
   - existing global forecast/actual extrema (`dailyHighIndex`, `dailyLowIndex`,
     `forecast*`, `pastForecast*`, `actualHighIndex`, `actualLowIndex`), now compared by
     pixel gap instead of `boundaryWindow`;
   - **new:** the per-day actual extrema lists `extrema.actualDailyHighIndices` and
     `extrema.actualDailyLowIndices` (compared against `actualLabelTemps[aIdx]`).
   Skip any target index that is the candidate itself or already in `suppressedIndices`.

   Result for the bug: START(73°, idx 0) is redundant against the per-day ACTUAL_HIGH
   (72.7°, idx 30) — pixel gap ≈ 5 px, value diff 0.3° — so it is suppressed, leaving the
   informative `75°` and `72.7°`.

3. **Leave `checkLeftEdgeSuppression` and the START+actual pairing
   (`computeLeftEdgeStartOrdering`) untouched.** Suppression happens earlier in
   `collectLabelCandidates`, so a suppressed START simply never reaches the pairing/engine.

### Why the intended pairing still survives

`TemperatureLeftEdgeStartOrderTest` pairs START=64° with a nearby actual=66.9° — a **2.9°**
difference, above the `2f` value threshold — so the new check does **not** suppress it. The
value threshold cleanly separates "genuinely different boundary value worth pairing" from
"near-duplicate redundant edge label." Shared code means both Android and desktop benefit;
no desktop-specific change.

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
  - `checkRedundantPairSuppression` (~L534-587), `computeRedundantPairWindow` (~L45-56),
    `collectLabelCandidates` call site (~L251) — already has `hours`, `widthPx` in scope.
- Tests:
  - `shared/src/test/.../TemperatureLeftEdgeStartOrderTest.kt` — must still pass (pairing).
  - `shared/src/test/.../TemperatureLabelSuppressionTest.kt` — must still pass.
  - **Add** a regression test: a dense 3-day-style series where START (idx 0) and a per-day
    ACTUAL_HIGH a few indices away are pixel-near and within 2°; assert START is suppressed
    (and a control where they differ by >2° asserts START survives).

## Verification

1. Unit tests:
   `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.*"`
   (confirm the two existing left-edge/suppression tests pass and the new one passes).
2. Build & install: `./gradlew installDebug`.
3. Trigger a widget redraw (resize, or `adb -s emulator-5554 shell am broadcast` the
   refresh action) and capture the graph:
   `adb -s emulator-5554 exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`
   — confirm the left edge now shows only `75°` and `72.7°`, no `73°`.
4. Confirm via logs that START is dropped for the right reason:
   `adb -s emulator-5554 logcat -d | grep -E 'LabelSuppressed.*role=START.*REDUNDANT'`
   and that the wider graph (other days, right edge) is unchanged.
