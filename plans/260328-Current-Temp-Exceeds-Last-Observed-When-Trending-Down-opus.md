# Fix: Current Temp Exceeds Last Observed When Trending Down

## Context

The widget header shows 75.4°F when the last observed temp is 75.1°F and temperature is trending down. The root cause is a **stale delta**: `CurrentTemperatureResolver` caches the delta correction (`observed - forecastAtObsTime`) and reuses it across forecast data refreshes. When the API returns updated hourly forecasts, the old delta applied to new forecast values overshoots.

**Formula**: `displayTemp = estimatedTemp + appliedDelta`

The delta is only recalculated when `observedAt` changes (new observation from API). But hourly forecast data can refresh independently, making the stored delta incorrect relative to the new forecast values.

## Fix

**Always recalculate the raw delta from current forecast data**, using the stored state only for decay timing.

### File: `CurrentTemperatureResolver.kt`

**1. Remove early decay application (lines 113-125)**

Replace the block that decays the stored delta before the observation check:
```kotlin
var appliedDelta = scopedStoredDelta?.let { ... getDecayedDelta(...) ... }
```
with:
```kotlin
var appliedDelta: Float? = null
```

**2. Restructure the observation block (lines 130-184)**

Remove the `hasNewObservedReading` gate. Always:
1. Interpolate `estimatedAtObsTime` from current hourly forecasts at `observedAt`
2. Compute `delta = observedCurrentTemp - estimatedAtObsTime`
3. Determine decay anchor: use `scopedStoredDelta.updatedAtMs` when observation is the same (decay continues), or `observedAt` when observation is new
4. Apply decay to the freshly computed delta
5. Emit `updatedDeltaState` with the fresh delta + correct anchor

### File: `CurrentTemperatureResolverTest.kt`

**Update 3 existing decay tests** (lines 104, 142, 222): These use `observedAt = 1000L` (epoch 1970), which has no forecast coverage. The fix will now try to interpolate at that time and get null, triggering the fallback path. Two options:
- **Option A (preferred)**: Change `observedAt` to a realistic time within the forecast window (e.g., `nowMs(now.withMinute(0))`) and adjust assertions to match the recalculated delta
- **Option B**: Accept that these tests now exercise the fallback path, and adjust assertions accordingly

**Add new test**: `resolve recalculates delta when forecast data changes but observation stays same` — Two successive `resolve()` calls with different forecast data but identical observation, verifying the delta adjusts.

## Verification

1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.CurrentTemperatureResolverTest"`
2. Build and install on emulator: `./gradlew installDebug`
3. Verify on emulator: current temp should not exceed last observed when trending down
