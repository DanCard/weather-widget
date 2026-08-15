# Draw the actual low under its trough, not in the crook

**Date:** 2026-08-15
**Device:** Samsung Fold (SM-F936U1), hourly graph, widget id **345**
**Request:** "60.9 low label for actual temp graph: what do you think about trying to get that
label to draw underneath the graph?"
**Commit:** `4e395caf` · **Plan:** [plans/260815-draw-actual-low-under-its-trough.md](../plans/260815-draw-actual-low-under-its-trough.md)
**Follow-up to:** [260815-samsung-actual-low-label-drawn-on-observed-line.md](260815-samsung-actual-low-label-drawn-on-observed-line.md)

Done and confirmed on the Fold.

## It took two changes, not one

The rule diagnosed up front was real but wasn't what held *this* label up:

1. **`computeForcedAboveLowIndices`** — gated on actual contention, as planned. It now skips the
   flip once the two lows' anchors are a label-height-plus-gap apart, since two lows both sitting
   below their own curves can't invert (monotonic `tempToY`).

2. **`computeLeftEdgeStartOrdering`** — the one actually pinning `60.9°` above. Device logs showed
   it reaching `PlaceAccept` with *no* curve-fit pass at all, meaning a forced placer claimed it,
   and the `forceAbove` branch is skipped for labels in `leftEdgeOrder`. That render was the only
   one that accepted a `START` label; the morning's diagnosis named the other rule because its
   render had none.

The fix there was to change the *remedy*, not the goal. That rule prevented a cooler forecast label
reading above a warmer actual one by sending the pair to opposite sides. But opposite sides is only
one way to get correct order — putting both on the **same** side gets it for free, and leaves the
low under its trough. The pair now adopts the actual label's natural side and START joins it there.

## On device

```
PlaceAccept: role=ACTUAL_LOW idx=7 step=0 above=false leader=false
PlaceAccept: role=START      idx=0 step=0 above=false leader=false
```

`60.9°` sits in the clear band beneath its trough, off both curves, no longer crowding `62°`; the
order still reads 60.9 → 60 → 59 down the canvas.

## Test fallout — three changes, all deliberate, and one worth knowing about

- The morning's flip regression was re-fixtured: its 67.4-vs-65 lows are ~31px apart, so the new
  gate correctly declines to flip them. Now 67.4 vs 66.4 at ~10px.
- Its sibling, `still flips above when the forecast curve dips below the valley`, **asserted
  behaviour the engine does not have.** Its forecast sat 6-7° (~65px) below a 12px label box, so it
  never touched the below-box it claimed to test — it was passing on the ordering rule, duplicating
  the other test. Since 2026-06-14 a blocked below routes to the tight below-trough hug instead of
  flipping. Renamed and re-fixtured to pin that.
- `TemperatureLeftEdgeStartOrderTest` re-aimed from the old remedy to the contract. Its `baselineY`
  ordering assertion — the actual user-visible requirement — passed unchanged throughout; only the
  two `placedAbove` assertions encoding the old mechanism changed.

New regression test verified failing against the pre-change source. `:shared` 813 green,
`:app`/`:desktop` compile clean.

## Debugging notes worth keeping

The engine's placement breadcrumbs are `Log.v` behind a logcat tag filter, so `adb logcat` looks
empty during a live render until you run:

```bash
adb shell setprop log.tag.TempLabelEngine VERBOSE   # also TempLabelResolver, CurveFitPlacer
```

They're never persisted, so `app_logs` won't have them either.

Also, the hourly widget on the Fold is id **345** — the 352 in the morning's notes is stale, and
refreshes aimed at it did nothing.
