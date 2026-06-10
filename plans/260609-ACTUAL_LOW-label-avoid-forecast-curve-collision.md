# Fix: Suppress START label when near a nearby extremum

## Context
On the Samsung hourly temperature graph, three labels cluster together at the left side: `61°` (START), `62°` (ACTUAL_LOW), and `60°` (LOW or raw actual). The START label is redundant when a nearby extremum (LOW, ACTUAL_LOW, etc.) is within 8 hours and 2°F of the graph's first point. The user wants START suppressed in this scenario, keeping only the meaningful 62° and 60° labels.

The existing `checkRedundantPairSuppression` in `TemperatureLabelResolver.kt` already handles `END`, `LOCAL`, and `ACTUAL_END` with exactly this logic — but `START` was missing from that case (falling into `else -> false`).

## Change (already applied)

**File:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt` ~line 319

Add `TemperatureRole.START` to the existing redundant-pair suppression case:

```kotlin
// Before:
TemperatureRole.LOCAL, TemperatureRole.END, TemperatureRole.ACTUAL_END -> {

// After:
TemperatureRole.LOCAL, TemperatureRole.END, TemperatureRole.ACTUAL_END, TemperatureRole.START -> {
```

The suppression fires when any extremum index (dailyHigh, dailyLow, forecastHigh, forecastLow, actualHigh, actualLow, etc.) satisfies:
- `abs(startIndex - extremumIndex) <= redundantPairWindow` (≤8 hours for 217-point WIDE graph)
- `abs(labelTemps[startIndex] - labelTemps[extremumIndex]) < 2.0f`

For the observed case: START idx=0 (61°) vs ACTUAL_LOW idx=7 (~60-62°) → distance=7 ≤ 8, diff ≤ 1°F → suppressed. ✓

---

# Fix 2: ACTUAL_LOW label prefers above placement to avoid forecast curve collision

## Context
After Fix 1, the `62°` (ACTUAL_LOW) label is placed below the valley of the actual (pink) curve, but the forecast (dashed white) curve passes through that same area, causing a visual collision. The user explicitly prefers the label above the actual temperature line.

`ACTUAL_LOW` is classified as a valley (`isValley = true`), so `preferAbove = !isValley = false` → tries below first. There's no special case to override this when the forecast curve occupies the below-space.

## Change

**File:** `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt` ~line 154

Add `ACTUAL_LOW` as an explicit above-preferring case before the valley fallback:

```kotlin
val preferAbove = when {
    forceAbove -> true
    candidate.role == TemperatureRole.ACTUAL_LOW -> true   // prefer above; forecast curve often occupies the below-space
    valueBasedRoles -> prefersAbovePlacement(candidate)
    else -> !geometry.isValley
}
```

The engine still tries below as a fallback (`directions = [true, false]`), so if above is blocked, it falls back to below normally. This is purely a preference/ordering change.

## Verification
- Build: `./gradlew installDebug`
- Check Samsung: labels at left side of hourly graph should show only `62°` and `60°`, not `61°`
- Confirm START still appears when it is NOT near any extremum (e.g. a morning that starts at a unique temperature far from today's low)
- Unit tests: `./gradlew testDebugUnitTest`
