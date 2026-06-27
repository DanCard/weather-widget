# Night rain label: nudge right+down when there's room

## Context
In the daily forecast view, the interstitial night-time rain-chance label (e.g. "15%") sits
between two day columns, tucked under the larger low-temp label (e.g. "56.8°"). Today the
placement only ever *removes* its vertical overlap as space opens up — a roomy column still hugs
the low temp almost exactly like a cramped one. The user wants: keep the snug tuck when space is
tight (it looks good there), but when there's room, push the label a couple of pixels RIGHT and
DOWN so it reads as its own label instead of hugging the temperature.

Both the desktop and Android daily views are meant to be identical, and their placement constants
already live in one shared file, so the change is made once in shared + applied in both renderers.

## Mechanics (already in place)
A single `tightFraction` (0 = roomy, 1 = cramped) is derived from the room between the low-temp
label and the day-name row. `roomFraction = 1 - tightFraction`. Scaling the new offsets by
`roomFraction` makes them collapse to 0 when tight (preserving today's tuck) and reach their full
"couple pixels" when roomy.

## Changes

### 1. `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`
Add two constants next to the existing `NIGHT_TUCK_*` block:
```kotlin
const val NIGHT_TUCK_ROOMY_RIGHT_DP = 2.5f
const val NIGHT_TUCK_ROOMY_DOWN_DP = 2.5f
```

### 2. Desktop — `desktop/.../DailyForecastGraph.kt` (night label block ~L348-437)
- After `tightFraction` is computed, add:
  `val roomFraction = 1f - tightFraction`
  `val roomyRightPx = DailyRainLabels.NIGHT_TUCK_ROOMY_RIGHT_DP * roomFraction * scale`
  `val roomyDownPx = DailyRainLabels.NIGHT_TUCK_ROOMY_DOWN_DP * roomFraction * scale`
- Add `+ roomyRightPx` to `shiftedCenterX` (flows through edge-fit + collision checks).
- At draw time, apply `roomyDownPx` to `finalNightTopY` and include it in the `hardBottomLimit` guard.

### 3. Android — `app/.../widget/DailyForecastRainLabelRenderer.kt`
- Add local const aliases `NIGHT_TUCK_ROOMY_RIGHT_DP` / `NIGHT_TUCK_ROOMY_DOWN_DP` (L19-26 block).
- In `drawNightRainLabel`: compute `roomFraction = 1 - tuck.tightFraction`, derive `roomyRightPx`/
  `roomyDownPx` (px), subtract `roomyRightPx` from `hNudgePx` (existing `- hNudgePx` => rightward),
  and add `roomyDownPx` to `resolvedBaseline`/`resolvedBottom` before the `hardBottomLimit` check.

## Tests
- `app/src/test/.../DailyForecastGraphRendererTest.kt` covers `resolveNightCollision`; add a
  regression asserting roomy vs tight yields a rightward + downward offset (or that tight = 0 offset).

## Verify
- `./gradlew :app:testDebugUnitTest --tests "*DailyForecastGraphRendererTest*"`
- Rebuild + restart desktop (`scripts/buildStart.sh`) and eyeball the today→tomorrow night label:
  tight column keeps the tuck; roomy column shows it shifted a couple px right and down.
