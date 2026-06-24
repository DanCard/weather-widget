# Fix: two stacked "actual" temperature labels at the left edge of the hourly graph

## Context

On the emulator's hourly temperature graph, **two pink "actual" labels stack at the left
edge**: `60.8°` directly above `58.9°` (confirmed by screenshot + logcat). Only one should
appear. The user's stated rule: *when the actual low is close to the left side, suppress the
left-side label.*

**Root cause (confirmed from `TempExtrema` logs):**
- The leftmost visible day (Tuesday) is a thin ~2-hour partial sliver (10pm→midnight) that
  descends monotonically into Wednesday's genuine overnight low.
- Logs: `ACTUAL_DAILY highIdxs=[0] highTemps=[60.76] lowIdxs=[26] lowTemps=[58.90]` and
  `SHOULDER_DROPPED idxs=[25]` (Tuesday's own raw low was already merged into Wednesday's
  deeper valley at idx 26).
- After shoulder-drop, Tuesday retains **only a per-day high at the boundary start index
  (idx 0 = 60.8°)**. That index passes `isActualLocalMax` *only* because of the symmetric
  left-edge exemption (idx 0 has no left neighbor, so `leftOk` auto-trues). It is the warm
  point where observation *begins*, descending straight into the real low — not a diurnal peak.
- The existing `degenerateLowDrops` (high/low round identically) doesn't fire — 60.8 vs 58.9
  are ~2° apart. The shoulder-drop walk doesn't fire — it only collapses *same-type*
  consecutive extrema (high vs low are opposite types).

**Intended outcome:** drop the spurious boundary high so only the genuine overnight low is
labeled, without regressing the deliberate "coldest-observed-point-at-the-left-edge" case (a
boundary *low* the user does want labeled — see `actual_low_left_edge_label` memory).

## Approach

Add a third member to the existing "this isn't a real per-day extreme" family in
`TemperatureExtrema.kt` (alongside `shoulderDrops` and `degenerateLowDrops`): a **boundary-high
drop**. Doing it here (in `:shared`, where `actualDailyHighIndices` is produced) fixes both the
Android `TemperatureGraphRenderer` and the desktop Compose `TemperatureGraph` at once, and leaves
the resolver's intentional ACTUAL_HIGH/ACTUAL_LOW exemptions untouched.

**Why not the resolver:** `TemperatureLabelResolver.checkLeftEdgeSuppression`
(`:491-508`) deliberately exempts ACTUAL_HIGH/ACTUAL_LOW to preserve genuine boundary lows.
Touching it risks the `actual_low_left_edge_label` regression. Removing the spurious index
upstream means it never becomes an ACTUAL_HIGH anchor at all.

### The rule (asymmetric by design)

Drop a per-day actual **high** `hi` when **all** hold:
1. `hi == actualStartIndex` (the observation boundary start — it's a local max only via the
   no-left-neighbor exemption).
2. The nearest retained actual low `firstLow = actualDailyLowIndices.minOrNull()` exists, sits
   to the right (`firstLow > hi`), and is **cooler** (`actualLabelTemps[firstLow] < actualLabelTemps[hi]`).
3. **No retained actual high lies between** `hi` and `firstLow` — nothing real separates them.
4. The observed curve **descends weakly-monotonically** from `hi` to `firstLow`
   (`actualLabelTemps[j+1] <= actualLabelTemps[j]` for all `hi <= j < firstLow`).

Conditions 3–4 are the robustness gate: a genuine separated daytime peak (curve dips then climbs
to a real afternoon high before the low) breaks monotonicity / has a retained high between, so it
is kept. Monotonic descent also can't physically span multiple diurnal cycles, so the rule is
self-bounding to ~1 day without a magic index window.

**No symmetric rule for a boundary low.** A coldest-observed point at the left edge (overnight
low landing at midnight in the 24h view) is genuine and must stay labeled. Document this in the
code comment.

## Changes

### `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`

Around lines 153–171 (the shoulder/degenerate block):

1. Rename the line-153 value `actualDailyHighIndices` → `shoulderedHighIndices`.
2. Update the `highIdxByDay` line (158) to read from `shoulderedHighIndices` (order-safe: only
   used for the degenerate-low partner check).
3. After `actualDailyLowIndices` is computed (line 164), add the boundary-high drop and the final
   `actualDailyHighIndices`:

```kotlin
// Drop a spurious LEFT-BOUNDARY high. A partial edge day that is a pure descending sliver (the
// observed data simply BEGINS at its warm point and falls straight into the next valley) keeps a
// per-day "high" at actualStartIndex. That index passes isActualLocalMax only because the left-edge
// exemption auto-trues leftOk (no left neighbour) — it is the warm START of a descent, not a
// diurnal peak. If the curve descends weakly-monotonically from that boundary high into the nearest
// retained actual low, with NO retained actual high in between, the high is redundant next to that
// low and stacks a second pink label at the graph edge. Drop it.
// ASYMMETRIC by design: never mirror this for a boundary LOW on an ascending sliver — a coldest-
// observed point at the left edge is a genuine value the user wants (see actual_low_left_edge_label).
val boundaryHighDrops = shoulderedHighIndices.filter { hi ->
    if (hi != actualStartIndex) return@filter false
    val firstLow = actualDailyLowIndices.minOrNull() ?: return@filter false
    if (firstLow <= hi) return@filter false
    if (actualLabelTemps[firstLow] >= actualLabelTemps[hi]) return@filter false
    if (shoulderedHighIndices.any { it in (hi + 1) until firstLow }) return@filter false
    (hi until firstLow).all { actualLabelTemps[it + 1] <= actualLabelTemps[it] }
}.toSet()
val actualDailyHighIndices = shoulderedHighIndices.filterNot { it in boundaryHighDrops }
```

4. Add a trace line matching the existing `DEGENERATE_DAY_LOW_DROPPED` / `SHOULDER_DROPPED` style:

```kotlin
if (boundaryHighDrops.isNotEmpty()) {
    Log.v(TAG, "BOUNDARY_HIGH_DROPPED idxs=${boundaryHighDrops.sorted()} " +
        "temps=${boundaryHighDrops.sorted().map { actualLabelTemps[it] }}")
}
```

### `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureExtremaIncompleteDayTest.kt`

Add three tests using the existing `Pt`/`hour`/`build` helpers (`transitionX = null`, the
panned/history view matching the bug):

- **Drop:** a descending boundary sliver (`60.8 @ 22:00 → 58.9 @ 23:00` crossing midnight into a
  genuine overnight low `58.4 @ 00:00`, then a real Apr-9 high `74`). Assert idx 0 is **not** in
  `actualDailyHighIndices`, the overnight low **is** in `actualDailyLowIndices`, and the real
  daytime high is still labeled.
- **Guard (coldest-at-edge):** an ascending boundary sliver starting at the coldest point
  (`50 @ 00:00`) warming up. Assert the boundary low (idx 0) is **kept** in
  `actualDailyLowIndices`.
- **Guard (separated peak):** a boundary point that dips then climbs to a real afternoon peak
  before the next low (non-monotonic). Assert the real daytime high is labeled and the rule did
  not over-fire.

## Verification

1. Unit tests (fast, no device):
   ```
   ./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureExtremaIncompleteDayTest"
   ```
   Expect the new drop test + both guard tests green, and the existing degenerate/incomplete-day
   tests still green.
2. Build + install, then confirm on the emulator the left edge now shows a **single** pink label
   (the genuine low) instead of `60.8°` stacked over `58.9°`:
   ```
   ./gradlew installDebug
   ```
   Re-capture: `adb -s emulator-5554 exec-out screencap -p > /tmp/ss.png && convert /tmp/ss.png /tmp/ss.jpg`
3. Confirm the trace fires in logcat: `adb -s emulator-5554 logcat -d | grep BOUNDARY_HIGH_DROPPED`
   (should report `idxs=[0]`).
4. Desktop parity is automatic (shared module) — optionally restart the desktop app to spot-check
   the same window.

## Key files
- `shared/.../graph/TemperatureExtrema.kt` — the fix (per-day extrema drop logic).
- `shared/.../graph/TemperatureExtremaIncompleteDayTest.kt` — regression tests.
- `shared/.../graph/TemperatureLabelResolver.kt:491-508` — reference only; the intentional
  ACTUAL_* left-edge exemption to preserve.
