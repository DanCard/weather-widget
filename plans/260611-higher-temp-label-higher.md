# Order the left-edge start label pair by temperature

## Context

On the zoomed-in hourly temperature view, the graph's left edge shows two co-located labels:
a gray **66°** forecast `START` label and a pink **66.9°** actual label. They read as inverted —
the cooler forecast value sits *above* the warmer actual value.

Confirmed from live placement logs:
- `START` idx 0, forecast 66.0°, `placedAbove=true` (y≈245, high on screen)
- `ACTUAL_LOW` idx 6, actual 66.9°, `placedAbove=false` (y≈328, below the line)

They don't collide (opposite sides of their lines, ~10px apart in x); the inversion is just the
product of two independent defaults: `prefersAbovePlacement` puts `START` above (66° is the local
max as the forecast line descends from the edge), while a valley-like `ACTUAL_LOW` defaults below,
and `computeForcedAboveLowIndices` only flips an actual low above when a *lower low* is nearby — the
cooler forecast START isn't a "low," so nothing reorders them.

**Intended outcome (user-confirmed scope: left-edge start pair only):** at the graph's left edge,
order the `START` (forecast) label and the nearest actual label by temperature — warmer above,
cooler below. So here: 66.9° actual moves above its line, 66.0° forecast moves below its line. The
rest of the heavily-tuned placement engine is left untouched.

## Change — `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`

Add a small helper mirroring the existing `computeForcedAboveLowIndices` pattern (line 71), computed
once before the placement loop:

```kotlin
private const val LEFT_EDGE_START_WINDOW = 8 // indices; actual label must be near idx 0

// Returns index -> placeAbove overrides for the left-edge START/actual pair, ordered by value.
private fun computeLeftEdgeStartOrdering(candidates: List<TempLabelCandidate>): Map<Int, Boolean> {
    val start = candidates.firstOrNull { it.role == TemperatureRole.START } ?: return emptyMap()
    val actualRoles = setOf(TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_END)
    val actual = candidates
        .filter { it.role in actualRoles && it.index != start.index && abs(it.index - start.index) <= LEFT_EDGE_START_WINDOW }
        .minByOrNull { abs(it.index - start.index) } ?: return emptyMap()
    val startVal = start.labelTemps[start.index]
    val actualVal = actual.labelTemps[actual.index]
    if (TemperatureLabelResolver.formatTemp(startVal) == TemperatureLabelResolver.formatTemp(actualVal)) return emptyMap()
    val startAbove = startVal > actualVal
    return mapOf(start.index to startAbove, actual.index to !startAbove)
}
```

Wire it as the **highest-priority** `preferAbove` override in `computePlacements` (the `when` at
~line 155, alongside `forcedAboveLows`):

```kotlin
val leftEdgeOrder = computeLeftEdgeStartOrdering(candidates) // computed near forcedAboveLows (~line 125)
...
val preferAbove = when {
    idx in leftEdgeOrder -> leftEdgeOrder.getValue(idx)
    forceAbove -> true
    valueBasedRoles -> prefersAbovePlacement(candidate)
    else -> !geometry.isValley
}
```

Notes:
- `actual.labelTemps` for an actual-role candidate is already the actual series (set in
  `collectLabelCandidates`), so `actual.labelTemps[actual.index]` is the displayed actual value and
  `start.labelTemps[start.index]` is the displayed forecast value — we compare exactly what's drawn.
- Both roles are in `CURVE_AVOIDANCE_ROLES`, so the forced direction is tried first and gracefully
  falls back if it can't fit; at the left edge there's headroom above the actual line and open space
  below the descending forecast line, so the swap fits.
- No-op when there's no actual label near the edge or the two values render identically.

## Tests — `shared/src/test/.../graph/` (mirror `TemperatureValleyBelowCascadeTest` harness)

Add a test that builds ~24 hours where the actual start value is warmer than the forecast start
value, with a small actual dip near idx 0 so an `ACTUAL_LOW` lands within the left-edge window.
Assert via `computePlacements`:
- the warmer actual label has `placedAbove == true`
- the cooler `START` label has `placedAbove == false`
- (sanity) the warmer label's `baselineY < ` the cooler label's `baselineY`

Also run the existing `TemperatureValleyBelowCascadeTest` / `TemperatureLabelCollisionOrderTest` /
`TemperatureLabelSuppressionTest` to confirm no regression.

## Verification

1. `./gradlew :shared:test` (full shared suite — engine + resolver tests).
2. `./gradlew installDebug`, then on the emulator broadcast
   `adb -s emulator-5554 shell am broadcast -a com.weatherwidget.ACTION_REFRESH -p com.weatherwidget`.
3. Screenshot (`screencap -p > /tmp/s.png && convert ... s.jpg`) of the zoomed hourly view; confirm
   the left edge now shows 66.9° (pink) above and 66° (gray) below.
4. Logs: `adb -s emulator-5554 logcat -d | grep LabelPlacementDebug` — expect the `START` row with
   `placedAbove=false` and the left-edge actual row with `placedAbove=true`.

## Risk
- Pure `:shared` placement-engine change, scoped to the single left-edge pair via an explicit
  index→direction override that the loop honors first; all other candidates keep their existing
  behavior. Desktop has a separate label reimpl and is unaffected.
