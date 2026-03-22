# Plan: Fix Counter-Intuitive Current Temperature Trend

The user reported a discrepancy where the "Current Temp" (top left) was higher than the "Last Fetch Dot" (last station observation), despite a downward forecast trend. Investigation revealed that the **linear delta decay** (which pulls the observation-forecast difference toward zero over 4 hours) is the cause. When a negative delta (station cooler than forecast) decays, it effectively adds heat to the displayed temperature.

## Objective
Prevent displayed temperature from rising/falling counter-intuitively by delaying the start of the delta decay process.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`: Contains the `getDecayedDelta` logic.

## Implementation Steps

### 1. Introduce Decay Grace Period
Modify `getDecayedDelta` in `CurrentTemperatureResolver.kt` to support a grace period (e.g., 60 minutes) where the full delta is preserved.
- `DELTA_DECAY_GRACE_PERIOD_MS = 60 * 60 * 1000L` (1 hour)
- If `elapsedMs < DELTA_DECAY_GRACE_PERIOD_MS`, return the full `rawDelta`.
- If `elapsedMs >= DELTA_DECAY_GRACE_PERIOD_MS`, calculate linear decay from the *end* of the grace period to the end of the total window.

### 2. Adjust Decay Formula
The new formula will be:
`remainingFraction = 1f - ((elapsedMs - GRACE) / (TOTAL_WINDOW - GRACE))`

### 3. Update Unit Tests
Update `CurrentTemperatureResolverTest.kt` to verify that the delta remains constant during the grace period and decays correctly thereafter.

## Verification & Testing
- **Log Audit**: Check `CurrentTempResolver` logs to ensure `decayedDelta` matches `rawDelta` for observations less than 1 hour old.
- **Manual Verification**: Reproduce the scenario on the emulator (if possible) and confirm the current temp is now logically consistent with the trend and the last observation.
- **Unit Tests**: Run `CurrentTemperatureResolverTest` to confirm the new logic.
