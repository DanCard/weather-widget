# Place the observed low ("60.4") tight below its trough instead of flipping above through the lines

## Context

On the 3-day hourly temperature graph the **observed low** label for the Wed→Thu overnight
trough ("60.4°", role `ACTUAL_LOW`, `lo=60.40@23:10`) is drawn **above** its valley, where it
runs *through* both the descending actual (pink) and forecast (dashed) lines.

Confirmed from on-device placement logs (emulator widget 52):
```
index=194 role=ACTUAL_LOW temperature=60.4 x=114.9 y=242.4 placedAbove=true
reason=above+curveFit(17.2px) displacementSteps=1
```
ACTUAL_LOW prefers *below* (it's a valley), but the space just below its anchor is rejected, so
`tryExactFitCurveAvoidance` falls through to the **above** direction and accepts an above placement
that's allowed to graze the curve by ~17px (`curveFit`). The result overlaps the lines.

There is clear space **just below the observed trough point**: at the trough x the actual line is at
its local minimum and the forecast's own dip is offset to the right, so directly beneath the trough
is open.

**Desired outcome (per user):** only in this conflict case (when the low would otherwise be placed
above / through the lines), drop the label **tight below the observed low point**, very close, with
**no leader line**. Keep the current behavior for lows that already place cleanly. This mirrors the
observed-**high** rule (`placeActualHighAboveCurve`) that always rides above its spike — the
downward counterpart, but scoped to the conflict case.

## Approach

Add a dedicated downward placement for `ACTUAL_LOW` that mirrors `placeActualHighAboveCurve`
(`TemperatureLabelEngine.kt:515`), and use it **as the fallback instead of the above-graze** — not
as an unconditional always-below (the user chose "only drop below on conflict").

### 1. New helper `placeActualLowBelowCurve` — `shared/.../graph/TemperatureLabelEngine.kt`
Mirror of `placeActualHighAboveCurve`, downward:
- Find the **lowest** visible actual point under the label's x-span (`max` py over
  `actualVisiblePoints` in `[clampedX-half, clampedX+half]`), falling back to `geometry.sy` — this is
  the trough floor the label hugs.
- Place the label **below** it with a *tight* gap (`TEMP_ACTUAL_LOW_BELOW_GAP_DP`, start at 0–1dp,
  tune on device) via `GraphLabelPlacementUtils.computeLabelVerticalPlacement(placeAbove=false, …)`.
- If `bottom > heightPx` (would fall off / into the footer band), shift **up** to clamp on-screen
  (symmetric to the high helper's `if (top < 0) shift down`).
- `drawLeaderLine = false`, `placedAbove = false` (no leader — per user). Add the `PlacedLabelMeta`
  with `isValleyBelow = true`.

### 2. Invoke it only in the conflict case — `TemperatureLabelEngine.kt` (curve-avoidance path)
`ACTUAL_LOW` is a `CURVE_AVOIDANCE_ROLES` member and runs through
`tryExactFitCurveAvoidance` (line ~298), whose `directions` are below-first for a valley. Today when
the **below** direction is blocked it falls through and the **above** direction places the grazing
label. Change it so that for `ACTUAL_LOW` (and `idx !in leftEdgeOrder` — preserve the left-edge
forced-above pairing, exactly as `placeActualHighAboveCurve` is gated), **before** accepting an
above placement, call `placeActualLowBelowCurve` and take it. Net effect: a clean below still wins
normally; the only change is the *fallback* flips from "above through the lines" to "tight below the
trough."
- Concretely: in `tryExactFitCurveAvoidance`, after the below direction returns
  `LABEL_OR_ICON_BLOCKED`/`GAVE_UP` for `ACTUAL_LOW`, route to `placeActualLowBelowCurve` and return
  `true` instead of continuing to the above direction. Confirm during implementation whether the
  block came from the forecast curve vs a real label/icon (logs showed no label/curve *rejection*
  for this idx, so the below block is the forecast-curve intrusion in the wider x-span) — the tight
  below-trough placement sidesteps it because it anchors to the actual minimum, beneath which the
  span is clear.

### 3. New gap constant — `shared/.../graph/GraphLabelPlacementUtils.kt`
`const val TEMP_ACTUAL_LOW_BELOW_GAP_DP = 1f` (sibling to `TEMP_ACTUAL_HIGH_ABOVE_GAP_DP`), tuned on
device to "very close."

### Notes / scope
- **Surgical, not symmetric-always:** unlike `ACTUAL_HIGH` (always routed to its helper), `ACTUAL_LOW`
  only uses the helper when the alternative is an above-graze. Clean below placements are untouched.
- **Left-edge ACTUAL_LOW** (`leftEdgeOrder` / `forcedAboveLows`) keeps its current treatment — the
  helper is gated on `idx !in leftEdgeOrder`, matching the high path.
- **Reuse**, don't reinvent: copy the structure of `placeActualHighAboveCurve` (curve-point scan,
  clamp, `PlacedLabel`/`PlacedLabelMeta` construction); only the direction, clamp edge, gap constant,
  and the no-leader flag differ.

## Files
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt` — add
  `placeActualLowBelowCurve`; route ACTUAL_LOW to it in the conflict fallback
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphLabelPlacementUtils.kt` — add
  `TEMP_ACTUAL_LOW_BELOW_GAP_DP`

## Verification

**Unit/Robolectric:** run `./gradlew :shared:test --tests "*TemperatureLabel*"` and
`:app:testDebugUnitTest --tests "*TemperatureGraphLabelPlacement*"`. Watch the existing ACTUAL_LOW
cases ("actual low label stays below dip even with significant icon overlap", left-edge low cases) —
they must stay green. Add a case: an ACTUAL_LOW valley with a forecast curve dipping below it lands
`placedAbove=false`, tight below the trough, no leader.

**On-device (the reported case):**
1. `./gradlew :app:assembleDebug`, verify the dex contains `WeatherWidgetApp` (the build-cache
   corruption check), then `adb install -r`.
2. Refresh widget 52/345 (THREE_DAY temperature). Screenshot per CLAUDE.md (PNG→JPG).
3. Confirm "60.4°" now sits just below the Wed→Thu trough, clear of the actual and forecast lines,
   with no leader. Tune `TEMP_ACTUAL_LOW_BELOW_GAP_DP` for "very close."
4. Check other lows are unchanged: Fri 56.8 (`ACTUAL_LOW idx=566`), the 57° forecast low, and any
   left-edge low.
