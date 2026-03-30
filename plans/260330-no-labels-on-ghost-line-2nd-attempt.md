# Fix: Spurious Temperature Labels from Station-Extrapolated Data

## Context
A label showing "79.4°" (later "79.5°") appears on the graph that shouldn't be there. It corresponds to a peak `actualTemperature` value in the future portion of the graph, set from station observation data that is extrapolated forward (+12 hours per station, per logs: `extra=12`). This extrapolated value drives the `dailyHighIndex`, placing a HIGH label floating above the forecast curve — i.e., on the ghost line — since no visible curve at that X position reflects that temperature.

Also addressed: a prior fix ensured label Y positions come from the label's own temperature value rather than ghost-line curve points; tests are added for both.

## Root Cause
In `TemperatureGraphRenderer.kt`, line 570:
```kotlin
val labelTemps = hours.map { it.actualTemperature ?: it.temperature }
```
This uses `actualTemperature` regardless of whether the hour is a genuine observation (`isActual == true`) or a forward extrapolation (`isActual == false`). Future hours with extrapolated station data pollute `labelTemps`, pushing the HIGH/LOW candidates into the future where they don't correspond to any visible curve.

## Fix 1 — `labelTemps` (line 570)
**File**: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

Change line 570 from:
```kotlin
val labelTemps = hours.map { it.actualTemperature ?: it.temperature }
```
to:
```kotlin
val labelTemps = hours.map { if (it.isActual) it.actualTemperature ?: it.temperature else it.temperature }
```
Restricts `actualTemperature` to genuine observations only. Future hours always use raw forecast temperature, so all labels correspond to a visible curve.

No other changes needed — `forecastLabelTemps` (line 571) already uses raw `temperature` only.

## Fix 2 — label Y positioning (already implemented)
Lines 625–628 now compute `sy` directly from `temps[idx]` using the Y-axis formula, decoupled from ghost-line `originalPoints`.

## Tests to Add
File: `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`

### Test 1: extrapolated actualTemperature on future hours does not produce HIGH label at extrapolated value
```
hours:
  past: isActual=true, actualTemperature=78f, temperature=76f  (genuine obs)
  future: isActual=false, actualTemperature=82f, temperature=77f  (extrapolated)

currentTime = at the transition between past and future

assert:
  - no label with temperature ~82f exists  (extrapolated value must not appear)
  - HIGH label temperature is ≤ 78f  (peak is from actual observations)
```

### Test 2: label Y positions are consistent with their temperature (ghost line fix)
```
hours: mix of past actual + future forecast, with a non-zero appliedDelta (e.g., 3f)

assert:
  - for every pair of labels (a, b):
      if a.temperature > b.temperature → a.y < b.y
  (higher temp = lower Y pixel value = higher on screen)
```

## Verification
1. `./gradlew installDebug`
2. Observe hourly graph on emulator — the spurious 79.4/79.5 label should be gone
3. Confirm HIGH/LOW labels still appear correctly on past actual data and forecast
4. `./gradlew testDebugUnitTest --tests "*.TemperatureGraphLabelPlacementRobolectricTest"`
