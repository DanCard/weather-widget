# Samsung: hourly-graph `60.9°` label drawn on top of the actual temperature line

**Date:** 2026-08-15
**Device:** Samsung Fold (SM-F936U1), widget id 352 (6-column, `widthPx=567`)
**Report:** "hourly graph temp. 60.9 temperature label should be drawn above or below actual
temperature line, not on top of it."

## Diagnosis

Device logs named the culprit directly:

```
TempLabelResolver: LabelAccepted: displayed="60.9" role=ACTUAL_LOW val=60.86627 idx=141
TempLabelResolver: LabelAccepted: displayed="59"   role=LOW        val=59.0     idx=142
TempLabelEngine:   PlaceAccept: role=ACTUAL_LOW idx=141 step=0 above=true leader=false
```

Two independent rules collided:

1. `computeForcedAboveLowIndices` flips the **warmer** low above when a **cooler** low label sits
   nearby, so the pair reads in temperature order. Here `60.9°` (ACTUAL_LOW, idx 141) was lifted
   above the cooler `59°` forecast LOW at idx 142.
2. A 2026-06-12 fix made ACTUAL_LOW ignore its own observed line for collision
   (`avoidanceActualPoints = emptyList()`), because sub-hourly jitter dipping a few px *below* the
   labeled minimum was wrongly shoving the label off-anchor.

Rule 2's rationale is purely a **below-direction** argument, but it was applied unconditionally.
The moment rule 1 sent the label above, the entire diurnal hump of the observed line became
invisible to the collision test — so the label was stamped across the pink line.

### Second defect, found by the regression test

`ActualExtremePlacers` finds its clearance by asking *which observed vertices fall inside the
label's x-span*. With hourly points 46px apart and a ~30px-wide label the answer is "none", so it
fell back to the hourly anchor and hugged the trough while the line ran through the box. On the
real device the observed line is 5-minute-sampled, so vertices did land inside and it happened to
work — the synthetic fixture exposed it. Same shape as the `knuq 73.4°` bug: a **sampling** bug
presenting as a placement bug.

## Changes (all in `:shared`, so desktop inherits them)

| File | Change |
|------|--------|
| `TemperatureLabelEngine.kt` | ACTUAL_LOW's exemption from actual-curve avoidance is now directional (`avoidanceActualPointsFor(placeAbove)`) — exempt only when placing below. A forced-above ACTUAL_LOW routes through `ActualExtremePlacers.place(placeAbove = true)`, the same forced placer ACTUAL_HIGH uses; unlike ACTUAL_HIGH it falls **through** to the normal loop when the placer can't fit, rather than dropping the label (below is still a legitimate slot for a low). |
| `CollisionTester.kt` | `allowedDipPxFor` takes `placeAbove`. ACTUAL_LOW's 0.5×labelHeight tolerance is also a "what sits under a valley" argument, so above it falls back to the standard 5dp graze. |
| `CurveIntrusion.kt` | New `curveYExtentInXSpan(points, left, right)` — y-extent of a polyline across an x-span, interpolating crossing segments, unclipped in y. |
| `ActualExtremePlacers.kt` | Uses `curveYExtentInXSpan` instead of vertex-membership testing. |
| `TemperatureActualLowOwnCurveGrazeTest.kt` | New regression test: `actual low flipped above by a cooler neighbour still clears its own observed line`. |

The `flipDecided` cascade case keeps its own full exemption via `allowFlippedAboveCurveGraze` —
untouched.

## Tried and deliberately reverted

`CurveFitPlacer`'s residual-intrusion guard measures the **near** edge of the clipped intrusion
(`newBounds.bottom - residual.maxY` when above), so it scores ~0 and effectively never fires.
Correcting it to `CollisionTester.curve`'s far-edge convention (`bottom - minY`) looks like an
obvious bug fix and **breaks 4 tuned tests** (`TemperatureLabelFetchDotHardBoundsTest`,
`TemperatureLabelHardBoundMinorOverlapTest`): every anchor-attached peak/valley label necessarily
has its own curve entering the box's lower corners, so the corrected measure rejects nearly every
curve-fit shift and labels fall through to `reason=FORCED`. Left as-is with an explanatory comment.

## Verification

- New test **verified failing against the pre-fix source**, passing after (guards against the test
  passing for the wrong reason).
- Full `:shared` suite: **812 tests, 0 failures**.
- `:app` and `:desktop` compile clean.
- On-device: `./gradlew installDebug`, then
  `adb shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider --ei appWidgetId 352`.
  Screenshot confirms `60.9°` now sits clear above the pink line. Logs show no `CurveFitPlacer`
  entry for ACTUAL_LOW in the new process — it is handled by the forced placer before that pass.
  User confirmed: "looks good on samsung phone".

## Note

While verifying, a mistyped `git stash push -m` caused an unrelated **pre-existing** stash (from
commit `28572f6`) to pop and conflict on a file deleted in HEAD. The tree was restored and the
stash entry remains intact in `git stash list`. No work was lost.
