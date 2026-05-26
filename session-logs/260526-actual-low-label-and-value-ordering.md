# Hourly Graph: Actual Low Label Restoration & Value-Ordered Placement

## Problem
On the hourly temperature graph, the **actual** temperature line's low point (red line bottom)
behaved inconsistently across devices:

- **Emulator / Pixel 7 Pro:** the actual low was **not labeled at all**.
- **Samsung (Galaxy Z Fold, `RFCT71FR9NT`):** the actual low *was* labeled, but stacked
  **inverted** — the colder forecast low (`52°`) sat *above* the warmer actual low (`53.7°`).

User preference: always show the actual low, and order a nearby low pair by value —
**higher temperature label above, colder below** (so label order matches point order).

## Diagnosis (from live logcat, not just code reading)
Two **independent stages** produced the two symptoms:

### 1. Suppression — why the emulator dropped the label
`TemperatureLabelResolver.checkRedundantPairSuppression` had an `ACTUAL_LOW` arm that collapsed
the actual low into `dailyLowIndex` when within `actualRedundantThreshold = 1.0°` and
`actualRedundantWindow = min(4, lastIndex/10)` indices.

Live logs captured the exact trip:
```
Emulator:  ACTUAL_EXTREMA lowIdx=158 lowTemp=53.17 ; daily LOW 53.0
           LabelSuppressed: role=ACTUAL_LOW idx=158 reason=REDUNDANT   (0.17° apart → dropped)
Samsung:   ACTUAL_EXTREMA lowIdx=48  lowTemp=53.73 ; daily LOW 52.0
           LabelAccepted:  role=ACTUAL_LOW idx=48                       (1.73° apart → kept)
```
The window scales with how many hourly samples fit the widget width, so the *same* weather scene
suppressed on one device and not the other — a geometry-dependent bug, not device-specific code.

### 2. Placement direction — why Samsung stacked them inverted
`TemperatureGraphRenderer.placeSingleLabel:439` used `preferAbove = !placement.isValley`. A low is
a valley → defaults to **below the point**, with no awareness of a neighbor. Both lows tried
"below," and the existing cascade left the colder `52°` visually above the warmer `53.7°`.

## Changes

### `TemperatureLabelResolver.kt`
- `checkRedundantPairSuppression` — the `TemperatureRole.ACTUAL_LOW` arm now returns `false`
  (never redundant). The observed low always keeps its own label. Safe because `ACTUAL_LOW` only
  ever resolves at an index distinct from `dailyLowIndex` (when the global min *is* an actual
  point, `resolveExtremaRole` returns `LOW` first), and identical-point dedup is still handled
  upstream by `deduplicateAnchors`. `ACTUAL_HIGH` and all other arms untouched.

### `TemperatureGraphRenderer.kt`
- New `computeForcedAboveLowIndices(candidates)` — returns the set of indices to flip to ABOVE.
  Scoped to **`ACTUAL_LOW` only**: lifts the actual low above a nearby low whose *rounded* value
  is **strictly lower** (window = `GraphLabelPlacementUtils.NEARBY_LABEL_WINDOW = 4`). Equal
  rounded values do not flip.
- `placeTemperatureLabels` computes the set after `sortLabelCandidates` and passes membership.
- `placeSingleLabel` gains `forceAbove: Boolean = false`; the decision became:
  ```kotlin
  val preferAbove = when {
      forceAbove -> true
      valueBasedRoles -> prefersAbovePlacement(candidate)
      else -> !placement.isValley
  }
  ```
  Everything downstream (`directions`, step/cascade loop, leader lines, essential force-place)
  already honors `preferAbove`. Added `import kotlin.math.roundToInt`.

### Why scoped to ACTUAL_LOW (not "any warmer low goes above")
The broader rule broke `TemperatureValleyBelowCascadeTest` (1 of 817 tests). That file is the
**only** coverage of `tryValleyBelowCascade` (horizontal-shift valley de-collision). Its scenario
is the mirror image (forecast `52°` higher, actual `50°` lower); the universal rule pushed the
forecast above and silently retired the cascade path. User chose the **narrow rule** — it fixes
the real case, keeps the cascade and its sole test intact, and changes the least behavior.

## Tests
`TemperatureLabelSuppressionTest.kt` — two new cases (use the existing MockK + `renderGraph` +
`onLabelPlaced` harness):
- `ACTUAL_LOW is retained when near daily low` — daily LOW 52° @ idx10, actual 52.5° @ idx8
  (0.5° / 2 idx apart): asserts both LOW and ACTUAL_LOW are placed.
- `nearby low pair is ordered by value with higher actual low above` — actual 56° @ idx8 over a
  strictly-lower forecast 52° @ idx10: asserts `actualLow.placedAbove == true` and
  `dailyLow.placedAbove == false`.

Full widget suite: **817/817 pass** (`./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.*"`).

## End-to-end verification (both devices, live)
Build/install: `./gradlew installDebug` (3 devices). Forced a redraw without navigating via:
```
adb -s <id> shell am broadcast -a com.weatherwidget.ACTION_REFRESH \
    -n com.weatherwidget/.widget.WeatherWidgetProvider --ei appWidgetId <wid>
```
(Plain `APPWIDGET_UPDATE` broadcasts are rejected by the system; `ACTION_REFRESH` is the working
trigger. Widget IDs from `dumpsys appwidget`: emulator=40, Samsung=346. Samsung `exec-out
screencap` returns a malformed PNG — use the `screencap → /sdcard → adb pull` route.)

Logs after fix:
```
Emulator: LabelAccepted: role=LOW idx=156 val=53.0 ; LabelAccepted: role=ACTUAL_LOW idx=158 val=53.17
Samsung:  LabelAccepted: role=LOW idx=46 val=52.0  ; LabelAccepted: role=ACTUAL_LOW idx=48 val=53.73
```

| Device | Before | After |
|--------|--------|-------|
| Emulator | only `53°` (actual low suppressed) | `53.2°` actual above, `53°` forecast below |
| Samsung | `52°` above, `53.7°` below (inverted) | `53.7°` actual above, `52°` forecast below ✅ |

## Caveat
The value-ordering flip only fires when the two lows differ by ≥1° rounded (Samsung case). In the
current emulator scene both round to `53°`, so the flip didn't fire — the actual low landed above
only because the below slot was already taken by the forecast low.

## Files Touched
- `app/src/main/java/com/weatherwidget/widget/TemperatureLabelResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- `app/src/test/java/com/weatherwidget/widget/TemperatureLabelSuppressionTest.kt`

## Status
Changes staged in working tree, not committed. Plan file:
`~/.claude/plans/emulator-hourly-graph-dynamic-quiche.md`.
