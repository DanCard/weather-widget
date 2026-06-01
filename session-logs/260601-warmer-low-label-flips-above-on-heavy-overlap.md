# Warmer low-temperature label flips above the line on heavy overlap

**Date:** 2026-06-01
**Device:** Samsung Galaxy Z Fold (SM-F936U1, adb serial `RFCT71FR9NT`)
**Area:** Temperature graph label placement (`TemperatureGraphRenderer`)

---

## Problem report

On the Samsung Fold, two temperature labels overlapped at the bottom of the graph:

- **History actual low: 53.2°** (the observed `ACTUAL_LOW`)
- **Forecast low: 54°** (the forecast daily `LOW`)

They sat on top of each other. The user's desired rule: *in the case of overlap, draw the
higher temperature (54°) label above the graph line.*

---

## Investigation (live device logs, not just source reading)

Per the project debugging guideline, I pulled the actual placement decisions off the device
rather than reasoning purely from code. Refreshing the widget and reading
`TempGraphRenderer` / `TempLabelResolver` logs:

```
LabelAccepted: role=LOW        idx=36 val=54.0      → forecast daily low, screen x≈94.5
LabelAccepted: role=ACTUAL_LOW idx=59 val=53.2      → observed low,       screen x≈116.2
ACTUAL_LOW idx=59 → placed below (y=365.9)
LOW        idx=36 → LabelCascade option1-accepted ratio=0.70 → placed below (y=357.6), reason=below-valley-overlap
```

### Root cause

1. The two lows are **23 array-indices apart** (different days) but only **~22px apart** on
   the compressed multi-day Fold graph, while each label box is ~54px wide.
2. The label *survival* logic (`TemperatureLabelResolver`) works in **index** space; the
   label *placement* logic (`TemperatureGraphRenderer`) works in **pixel** space. The bug
   lives in the gap.
3. The only existing "lift the warmer low above a colder one" mechanism,
   `computeForcedAboveLowIndices`, tests proximity with `NEARBY_LABEL_WINDOW = 4` **indices**.
   23 apart ≠ "nearby", so the flip never even got considered for a cross-day pixel collision.
4. Both lows therefore fell to the below-placement path. The colder `ACTUAL_LOW` was placed
   first. The warmer `LOW` then collided, tried a horizontal shift (couldn't separate — needs
   54px, has 22px), and accepted the cascade's **last-resort `option1` branch**
   (`VALLEY_VS_VALLEY_OVERLAP_RATIO = 0.85`). 0.70 ≤ 0.85 → accepted → the 70% overlap.

So nothing was "broken" — it was hitting the deliberate last-resort overlap allowance.

---

## Design decision

Asked the user how aggressive the flip should be:

- **Chosen: "Only on heavy overlap"** — keep both labels below when a small shift/overlap
  resolves it cleanly (preserves the compact look and the existing test); flip the warmer one
  above only when they'd still heavily overlap below (the `option1` case).
- Rejected: "Always warmer-above" — would have changed the both-stay-below behavior and broken
  `TemperatureValleyBelowCascadeTest`.

The chosen rule maps exactly onto the `option1` branch: that branch *is* the
"couldn't separate, accepting heavy overlap" situation. All cleaner resolutions
(horizontal shift, `option2` ≤0.65) return earlier and stay untouched.

---

## Implementation

### 1. `TemperatureGraphModels.kt`
Added `val temperature: Float` to `PlacedLabelMeta` so the cascade can compare the value being
placed against the value of the label it collides with.

### 2. `TemperatureGraphRenderer.kt`
- New sealed result for the cascade:
  ```kotlin
  private sealed class ValleyCascadeOutcome {
      data class Below(val result: CascadeResult) : ValleyCascadeOutcome()
      object FlipAbove : ValleyCascadeOutcome()
      object None : ValleyCascadeOutcome()   // == old null
  }
  ```
- `tryValleyBelowCascade` now returns `ValleyCascadeOutcome`. The "below-shifted" and
  "below-relaxed" (`option2`) paths return `Below(...)`. In the `option1` block, before
  accepting the heavy overlap:
  ```kotlin
  val currentVal   = candidate.labelTemps[candidate.index].roundToInt()
  val collidingVal = collidingMeta.temperature.roundToInt()
  if (currentVal > collidingVal) return ValleyCascadeOutcome.FlipAbove   // strictly warmer
  ```
  Ties and colder labels keep the existing below-overlap accept.
- `placeSingleLabel` gained a `var flipDecided = false`. On `FlipAbove` it sets the flag and
  `continue`s (does **not** place below, and does not record a forced-below fallback), letting
  the loop proceed to the `placeAbove = true` iteration. The cascade call is guarded with
  `&& !flipDecided` so it can't re-fire on later displacement steps. The warmer label then
  lands above with a leader line via the normal above-placement path.
- Added a `flip-above-warmer` log line for parity with the existing `option1`/`option2` logs.
- Updated the four `PlacedLabelMeta(...)` construction sites to pass `temperature = temps[idx]`.

### 3. `TemperatureLabelCollisionOrderTest.kt`
Added `warmer forecast low flips above a heavily overlapping colder actual low`, asserting the
warmer label is `placedAbove` and the colder stays below.

**Robolectric test gotcha:** `measureText` is stubbed, so label boxes are only a few px wide
and adjacent-index labels never overlap horizontally at realistic spacing. Worked around it by
giving the forecast low a **flat run** (idx 24–26 = 54°) so the `LOW` label's `centerOfRun`
lands on the *same index* (same X) as the `ACTUAL_LOW` point — guaranteeing the thin boxes
overlap horizontally. A high of 81° widens the range so the 0.8° gap maps to a ~0.7 vertical
overlap ratio (the `option1` band). See the existing `renderer_test_color_is_zero` learning.

---

## Why existing tests stay green
- `TemperatureValleyBelowCascadeTest` (52°/50°, narrow 300px): resolves via the horizontal-shift
  `Below(...)` path **before** `option1`, so it never reaches the flip — both stay below.
- `TemperatureLabelCollisionOrderTest.when two valleys collide…`: asserts only relative ordering;
  flipping the warmer above keeps the colder below, so it still holds.
- `TemperatureGraphLabelPlacementRobolectricTest` "actual low stays below dip": single low, no
  colliding pair → no flip.
- `computeForcedAboveLowIndices` (index-based ACTUAL_LOW flip) left untouched; this is an
  independent, screen-geometry-driven escape hatch.

---

## Verification

- **Unit/robolectric:** all 50 `TemperatureGraph*` renderer tests pass (incl. the both-stay-below
  guard and the new regression test).
- **On device (`RFCT71FR9NT`):** after `installDebug` + `ACTION_REFRESH`, logs now read:
  ```
  ACTUAL_LOW idx=59 → below, y=365.9 (unchanged)
  LabelCascade: role=LOW flip-above-warmer current=54 colliding=53 collidingRole=ACTUAL_LOW ratio=0.70
  LOW idx=36 → placedAbove=true, y=292.9, reason=above+1
  ```
  Screenshot confirmed visually: 54° above the line, 53.2° below, no overlap.

### Before / after

| Label | Before | After |
|-------|--------|-------|
| `ACTUAL_LOW` 53.2° | below, y=365.9 | below, y=365.9 (unchanged) |
| `LOW` 54° | below `below-valley-overlap`, y=357.6, **70% overlap** | **above** `above+1`, y=292.9, leader line |

---

## Files changed
```
app/src/main/java/com/weatherwidget/widget/TemperatureGraphModels.kt          |   1 +
app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt        | 112 +++++++---
app/src/test/java/com/weatherwidget/widget/TemperatureLabelCollisionOrderTest.kt | 58 +++++
```

## Follow-ups / notes
- Not committed — left in the working tree for review.
- The `flip-above-warmer` debug log is kept for now (consistent with the existing gated
  cascade logging); remove after a few days of monitoring if desired.
- Plan file: `~/.claude/plans/vectorized-seeking-wave.md`.
- Memory: `warmer_low_flips_above_on_heavy_overlap.md`.
