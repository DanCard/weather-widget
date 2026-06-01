# Plan: Lift the warmer low-temperature label above the line on heavy overlap

## Context

On the Samsung Galaxy Z Fold (SM-F936U1), the temperature graph draws two low labels
that heavily overlap at the bottom: the **observed actual low (53.2°)** and the
**forecast daily low (54°)**. Live `TempGraphRenderer` logs confirm the cause:

```
LabelAccepted: role=LOW        idx=36 val=54.0     (forecast daily low, screen x≈94.5)
LabelAccepted: role=ACTUAL_LOW idx=59 val=53.2     (observed low,       screen x≈116.2)
ACTUAL_LOW idx=59 → placed below (y=365.9)
LOW        idx=36 → LabelCascade option1-accepted ratio=0.70 → placed below (y=357.6)
```

The two lows are 23 array-indices apart (different days) but only ~22px apart on the
compressed multi-day graph, while each label box is ~54px wide. Because
`computeForcedAboveLowIndices` tests proximity by **array index** (`NEARBY_LABEL_WINDOW = 4`),
the existing "warmer low goes above" flip never fires for cross-day valleys. Both labels
fall to the below-placement path; the horizontal-shift attempt can't separate them
(needs 54px, has 22px), so the cascade accepts its **last-resort `option1` branch**
(valley-vs-valley, allows up to 0.85 vertical overlap) → the 70% overlap the user sees.

**Desired outcome (user decision: "only on heavy overlap"):** when two low labels would
otherwise hit that heavy last-resort overlap, lift the **warmer** one above the line and
leave the colder one below. Cases that resolve cleanly (horizontal shift or the small
`option2` ≤0.65 overlap) must keep their current compact both-below look.

## Approach

Make the decision inside `tryValleyBelowCascade`, at the exact `option1` branch that the
logs hit — this is precisely the "heavy overlap, couldn't shift" situation and nothing
else. All cleaner resolutions return earlier and are untouched.

### 1. Give placed labels a comparable temperature
`PlacedLabelMeta` (in `TemperatureGraphModels.kt`) currently carries `bounds`,
`isValleyBelow`, `role` — no value. Add `val temperature: Float`. Populate it at all
construction sites in `TemperatureGraphRenderer.kt` (lines ~554, ~593, ~613, ~777) with
the label's displayed value `candidate.labelTemps[candidate.index]` (in scope as
`temps[idx]` at each site).

### 2. Teach the cascade to signal a flip instead of accepting heavy overlap
In `tryValleyBelowCascade` (`TemperatureGraphRenderer.kt` ~797–868), change the return
type from `CascadeResult?` to a small sealed result, e.g.:

```kotlin
sealed class ValleyCascadeOutcome {
    data class Below(val result: CascadeResult) : ValleyCascadeOutcome()
    object FlipAbove : ValleyCascadeOutcome()
    object None : ValleyCascadeOutcome()   // == old null
}
```

- The "below-shifted", "below-relaxed" (`option2`, ≤0.65) paths return `Below(...)` (unchanged).
- In the `option1` block (`if (collidingMeta.isValleyBelow && overlapRatio <= VALLEY_VS_VALLEY_OVERLAP_RATIO)`),
  before accepting, compare values:
  ```kotlin
  val currentVal   = candidate.labelTemps[candidate.index].roundToInt()
  val collidingVal = collidingMeta.temperature.roundToInt()
  if (currentVal > collidingVal) return ValleyCascadeOutcome.FlipAbove   // strictly warmer → lift above
  // else fall through to existing below-overlap accept (ties stay below)
  ```
- The final fall-through returns `None`.

### 3. Place the flipped label above (deterministically, without re-triggering the cascade)
In `placeSingleLabel` (`TemperatureGraphRenderer.kt` ~507–606), where the cascade is
invoked inside the `outer@ for (step) { for (placeAbove in directions) … }` loop:

- Add a local `var flipDecided = false`.
- On the cascade call (only fires at `!placeAbove && isValley && step == 0`):
  - `Below(r)` → draw as today, `break@outer`.
  - `FlipAbove` → set `flipDecided = true` and **do not** place below; `continue` so the
    loop proceeds to the `placeAbove = true` iteration.
  - `None` → existing behavior (fall through).
- Guard the cascade call with `&& !flipDecided` so it is not re-entered on later steps.

Once `flipDecided` is true, the below iterations only see a normal collision (no cascade
accept), so the label is forced upward: `placeAbove=true` at step 0 may still hit the
curve (`overlapsCurve` for the curve-avoidance `LOW` role), and step 1 clears it and
draws above with a leader line — matching the Samsung log geometry (above bounds ~270–298,
clear of the curve at y≈322 and of the colder low at y≈343–371). `LOW` is `isEssential`,
so the existing `forceBounds` fallback still guarantees placement if above is crowded.

Add a `Log.d(TAG, "LabelCascade: role=… flip-above-warmer current=… colliding=…")` line in
the flip branch for parity with the existing cascade logging.

## Why this is safe for existing tests
- `TemperatureValleyBelowCascadeTest.valley below cascade prefers horizontal shift when overlap is partial`
  (52°/50°, narrow 300px): resolves via the horizontal-shift `Below(...)` path **before**
  `option1`, so it never reaches the flip — both stay below, assertion holds.
- `TemperatureLabelCollisionOrderTest.when two valleys collide…`: asserts only that 50 sits
  below 52. Even if it ever reached `option1`, flipping the warmer 52 above keeps 50 below
  → `low50.y > low52.y` still holds.
- `TemperatureGraphLabelPlacementRobolectricTest` "actual low stays below dip" (single low,
  no colliding warmer/colder low pair): no `collidingMeta`, so no flip.
- The existing `computeForcedAboveLowIndices` (warmer ACTUAL_LOW above colder neighbor by
  index) is left as-is; this change is an independent, screen-geometry-driven escape hatch.

## Critical files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphModels.kt` — add `temperature` to `PlacedLabelMeta`.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — sealed outcome,
  `option1` flip decision, `placeSingleLabel` flip handling, PlacedLabelMeta call-site updates, log line.
- `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt` — read-only reference
  (`VALLEY_VS_VALLEY_OVERLAP_RATIO` / `VALLEY_BELOW_LABEL_OVERLAP_RATIO`); no change expected.

## Verification
1. **Unit/robolectric tests** (must stay green):
   ```
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureValleyBelowCascadeTest" \
     --tests "com.weatherwidget.widget.TemperatureLabelCollisionOrderTest" \
     --tests "com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest"
   ```
2. **New regression test** in `TemperatureLabelCollisionOrderTest` (or a new file): reproduce
   the cross-day heavy-overlap case — a forecast `LOW` and an `ACTUAL_LOW` ~23h apart that
   land close in X on a wide graph — and assert the warmer label is `placedAbove == true`
   while the colder stays below.
3. **On-device confirmation (Samsung `RFCT71FR9NT`)**:
   ```
   ./gradlew installDebug
   adb -s RFCT71FR9NT logcat -c
   adb -s RFCT71FR9NT shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider
   adb -s RFCT71FR9NT logcat -d -s TempGraphRenderer | grep -E "role=LOW|flip-above"
   ```
   Expect `LOW … placedAbove=true` (was `below … reason=below-valley-overlap`).
   Capture a screenshot (convert PNG→JPG per CLAUDE.md) to confirm 54° now sits above the line, 53° below.
