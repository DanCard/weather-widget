# Plan: Delete Delta Decay Logic

The "Delta Decay" feature adds unnecessary mathematical complexity and can lead to counter-intuitive temperature trends. This plan removes the linear decay logic and transitions the widget to a simpler "Fixed Delta" model, where the forecast-observation difference is applied fully until it expires or is updated by a new measurement.

## Objective
Simplify current temperature resolution by removing delta decay and its associated state overhead.

## Key Files
- `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureDeltaState.kt`: Model for stored delta state.
- `app/src/main/java/com/weatherwidget/widget/CurrentTemperatureResolver.kt`: Main logic for applying deltas.
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`: Persistence logic for delta state.
- `app/src/test/java/com/weatherwidget/widget/CurrentTemperatureResolverTest.kt`: Unit tests.

## Implementation Steps

### 1. Simplify Data Model
- In `CurrentTemperatureDeltaState.kt`, remove the `updatedAtMs` field.
- `lastObservedAt` will be used as the single source of truth for both matching new readings and determining expiration.

### 2. Refactor CurrentTemperatureResolver
- Remove constants `DELTA_DECAY_WINDOW_MS` and `DELTA_DECAY_GRACE_PERIOD_MS`.
- Introduce `MAX_DELTA_AGE_MS = 4 * 60 * 60 * 1000L` (4 hours) to prevent using extremely old offsets.
- Delete `getDecayedDelta` function and `DeltaDecay` data class.
- Update `resolve` function:
    - If a scoped stored delta exists and `nowMs - lastObservedAt < MAX_DELTA_AGE_MS`, apply the `rawDelta` fully.
    - Otherwise, apply 0 delta.
    - Simplify the creation of `updatedDeltaState` (remove `updatedAtMs`).

### 3. Update Persistence Layer
- In `WidgetStateManager.kt`, update `setCurrentTempDeltaState`, `clearCurrentTempDeltaState`, and `readCurrentTempDeltaState` to reflect the removal of `updatedAtMs`.
- Remove the `KEY_CURRENT_TEMP_DELTA_UPDATED_AT_PREFIX` constant and usage.

### 4. Update Tests
- In `CurrentTemperatureResolverTest.kt`:
    - Remove tests that verify linear decay slopes (`resolve linearly decays stored delta after grace period`).
    - Add/update tests to verify that the full delta is preserved until the 4-hour expiration threshold.
    - Verify that the delta is discarded after 4 hours.

## Verification & Testing
- **Compilation**: Ensure the project builds successfully.
- **Unit Tests**: Run `CurrentTemperatureResolverTest` and `WidgetStateManagerTest`.
- **Manual Verification**: Verify on emulator that the current temperature stays logically consistent with the last observation (forecast trend + constant offset) and does not "drift" back to forecast over time.
