# Shorten the today-low leader line at the NOW valley (Samsung) + more label logging

## Context

On the 3-day hourly temperature graph, **today's actual low ("58.8°")** sits in the valley right
at the **NOW** indicator. Its label is dropped a full label-height below the valley with a long
pink leader line down to the axis row, leaving an ugly gap.

### Confirmed root cause (live Samsung `RFCT71FR9NT` logcat + screenshot, 2026-06-18)

`TempGraphRenderer: LabelPlacementDebug` for the offending label:

```
index=615, role=ACTUAL_LOW, temperature=58.78, x=379.3,
placedAbove=false, reason=below+1, displacementSteps=1, baselineY=383.2   (valley anchorY=325.4)
```

`TempLabelEngine: ExactFitPreCheck` for the same index:

```
ACTUAL_LOW idx=615 placeAbove=false baseBounds=(348.3,328.5,410.3,359.0) labelBlocker=true hardBlocker=true
ACTUAL_LOW idx=615 placeAbove=true  baseBounds=(348.3,288.8,410.3,319.4) intrusion=minY=288.3 maxY=319.9 hardBlocker=true
```

- The **only** obstacle in the natural just-below slot is a **reserved hard bound** — the fetch-dot
  value label "59.6°" near the NOW dot. (`labelBlocker=true` here just folds in the same hard hit:
  `effectiveLabelBlocker = (baseOverlapsLabel && !allowMinorLabelOverlap) || baseOverlapsHard`,
  `TemperatureLabelEngine.kt:621`.)
- **Above is genuinely blocked**: the forecast curve fills the trough (`intrusion` spans the whole
  above-label), so the low cannot flip above.
- With both directions blocked at step 0, the main displacement loop drops the label by
  `step * labelHeight` (`TemperatureLabelEngine.kt:293`) — a full label-height jump even though it
  only needed to clear a thin hard bound → the long leader.

Reserved hard bounds were made *absolutely* impenetrable in plan `260612-clash-of-low-temp-labels`
so that a forecast `LOW` colliding with the dot value would **flip above** the curve (fixing the
"631°" garble). That rule is correct for `LOW`/`FORECAST_LOW` (which *can* flip above) but wrong for
an `ACTUAL_LOW` pinned to a NOW-valley whose above-space is filled by the curve.

### Intended outcome

Let labels hug their anchor with a **bounded minor overlap** of a reserved hard bound instead of
dropping a full label-height — so the today-low label sits just below its valley with a short/no
leader. Apply this to **all minor-overlap-eligible roles**, not just `ACTUAL_LOW`: the minor-overlap
*budget* only ever permits whitespace-level (ascent/descent) overlap, so it never produces a visible
glyph collision (per user: "when allow minor overlap, there isn't an actual overlap"). Also add
diagnostic logging to make the broader class of label-placement issues debuggable.

## Approach

Treat a reserved-hard-bound overlap with the **same** minor-overlap tolerance the engine already
applies to placed labels and icons — i.e. reuse the existing
`GraphLabelPlacementUtils.shouldAllowMinorOverlap(role, overlap, labelHeight)` (eligible-role set +
`MINOR_OVERLAP_HEIGHT_RATIO = 0.45`). No new constant or helper. A hard bound stops being an
*absolute* blocker and becomes a blocker only when the overlap exceeds the minor budget.

This does **not** reopen the `260612` "631°" garble: that was a near-total overlap (both numbers on
the same spot), which far exceeds 0.45 × labelHeight, so a forecast `LOW` there still flips above.
The `260612` regression test remains the guard.

Rejected alternatives:
- *Scope to `ACTUAL_LOW` only*: user wants the allowance applied to everything.
- *Suppress the redundant low* (it's ~1° from the dot value): user wants the low kept and visible.
- *A new dedicated hard-bound ratio*: unnecessary; the proven 0.45 label/icon budget is the same
  whitespace-level tolerance and keeps one knob.

## Changes

### 1. Shared utils — `shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphLabelPlacementUtils.kt`

- Add a constant `HARD_BOUND_OVERLAP_RATIO` (start ~`0.5f`; tune against device logs) near the other
  `MINOR_OVERLAP_*` ratios (`:294-298`).
- Add a helper `fun isHardBoundMinorOverlapEligible(role) = role == TemperatureRole.ACTUAL_LOW`
  (kept deliberately narrow; expand later only with evidence).

### 2. Shared engine — `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

Main displacement loop (`:340-342`). Today `overlapsHard` is an unconditional collision:

```kotlin
val overlapsHard = reservedHardBounds.any { it.intersects(bounds) }
...
val hasCollision = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsIcon && !allowMinorIconOverlap) || overlapsCurve || overlapsHard
```

Change to allow a bounded overlap for `ACTUAL_LOW`, mirroring `allowMinorIconOverlap`:

```kotlin
val hardOverlap = if (overlapsHard) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, reservedHardBounds) else 0f
val allowMinorHardOverlap = overlapsHard &&
    GraphLabelPlacementUtils.isHardBoundMinorOverlapEligible(candidate.role) &&
    hardOverlap <= labelHeight * GraphLabelPlacementUtils.HARD_BOUND_OVERLAP_RATIO
...
val hasCollision = ... || overlapsCurve || (overlapsHard && !allowMinorHardOverlap)
```

This lets the `ACTUAL_LOW` place below at **step 0** (no leader) when the hard-bound overlap is
within budget. Because `directions` tries below before above for a valley, and forecast
`LOW`/`FORECAST_LOW` remain ineligible, those still flip above exactly as today.

Mirror the same allowance in the curve-avoidance pre-pass gate `checkExactFitBlockers`
(`:621`, the `baseOverlapsHard` term) so the pre-pass doesn't pre-emptively reject the below
direction for `ACTUAL_LOW`. The post-displacement re-check in `tryExactFitForDirection`
(`:705-706`) stays a hard reject (it only runs after a deliberate displacement; keeping it strict
avoids re-introducing leaders there).

### 3. Diagnostic logging (user request: "lots of label issues, add logging")

The current `LabelPlacementDebug` line says *that* a label was displaced but not *what blocked it*.
Add, gated by the existing `LOGGED_ROLES` / `shouldLogPlacement` so volume stays bounded:

- In the engine main loop, when a candidate position is rejected, log the blocking reason with the
  offending rect and overlap px, e.g.
  `Log.d(TAG, "PlaceReject: role=$role idx=$idx step=$step above=$placeAbove blocker=[label=$overlapsLabel/$labelOverlap icon=$overlapsIcon/$iconOverlap hard=$overlapsHard/$hardOverlap curve=$overlapsCurve] bounds=$bounds")`.
- Log the engine inputs once per `computePlacements`: `reservedHardBounds` rects, `fetchDotX`,
  `transitionX`, `heightPx` — so the obstacle geometry is visible without guessing
  (`Log.d(TAG, "EngineInput: heightPx=... fetchDotX=... hardBounds=${reservedHardBounds}")`).
- On a successful place, extend the existing accepted log to include `hardOverlap` and the chosen
  `allowMinorHardOverlap` so we can confirm the new path fired and the overlap stayed minor.

These are `Log.d` and route to logcat via the installed `AndroidLogSink` (and desktop sink).

## Tests

- **New shared test** `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelActualLowHardBoundOverlapTest.kt`
  (reuse the `runEngineTest` / `TestLabelTextMetrics` scaffold from
  `TemperatureValleyBelowCascadeTest.kt` / `TemperatureLabelFetchDotHardBoundsTest.kt`):
  1. `ACTUAL_LOW` at a valley + a `reservedHardBounds` rect overlapping its just-below slot **within
     budget** + curve filling above ⇒ assert `placedAbove == false`, `displacementSteps == 0`,
     `drawLeaderLine == false` (reproduces the fix; control without the allowance ⇒ `below+1`).
  2. Hard overlap **beyond** `HARD_BOUND_OVERLAP_RATIO` ⇒ still displaced (no unbounded overlap).
  3. **Regression guard**: forecast `LOW` with the same hard bound + fillable above ⇒
     `placedAbove == true` (the `260612` behavior is preserved). Re-run
     `TemperatureLabelFetchDotHardBoundsTest` to confirm.
- **Robolectric** (optional) extend
  `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`
  with a NOW-valley `ACTUAL_LOW` adjacent to the fetch-dot value label; assert via `onLabelPlaced`
  that the low's `displacementSteps == 0`.

## Verification

1. `./gradlew :shared:test --tests "*ActualLowHardBoundOverlap*" --tests "*FetchDotHardBounds*"`
   — new test green, `260612` test still green.
2. `./gradlew installDebug` to the Samsung (`adb -s RFCT71FR9NT`), trigger a redraw
   (`ACTION_REFRESH` broadcast), then:
   - `adb -s RFCT71FR9NT exec-out screencap` (via on-device file → convert to JPG) and confirm the
     58.8° label now hugs its valley with a short/no leader.
   - `adb -s RFCT71FR9NT logcat -d | grep -E "LabelPlacementDebug|PlaceReject|EngineInput"` and
     confirm `idx=615` now logs `displacementSteps=0` and `allowMinorHardOverlap=true` with a minor
     `hardOverlap`.
3. Sanity-check the other valleys in the same render (idx 4 / 355 `above+curveFit`) are unchanged,
   and that no forecast `LOW` regressed onto a dot value (no "NN N" garble).
