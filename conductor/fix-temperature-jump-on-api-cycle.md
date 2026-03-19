# Fix Current Temperature Discrepancy and Jump on API Cycle

## Background & Motivation
Users report that cycling through API sources back to NWS results in a different current temperature than when they started. Additionally, switching from the Daily view to the Hourly graph view causes another temperature "jump."

Investigation reveals:
1. **Delta Loss on Toggle**: `WidgetStateManager.toggleDisplaySource` calls `clearCurrentTempDeltaState`, erasing the correction factor (delta) between the hourly forecast and live observations for the previous source.
2. **Recalculation logic**: When cycling back to a source, `CurrentTemperatureResolver.resolve` sees `storedDeltaState == null` (or a `scopeMatch` failure) and recalculates the delta against the *current* wall-clock time's forecast, even if the last observation is old.
3. **Temporal Mismatch**: The delta is calculated as `observation - forecast(now)`. If the observation is 30 minutes old, it's being compared to the forecast for "now" instead of the forecast for 30 minutes ago.
4. **View Handler Inconsistency**: Different handlers (Daily vs. Temperature) may use slightly different `now` timestamps or data states, leading to minor interpolation differences (e.g., 85.16° vs 85.2°).

## Proposed Solution

### 1. Persistent Source-Specific Deltas
Modify `WidgetStateManager` and `CurrentTemperatureDeltaState` to support multiple deltas (one per source). Stop clearing the delta on source toggle. This ensures that when a user returns to NWS, the last known correction for NWS is still available and decaying naturally.

### 2. Accurate Delta Alignment
In `CurrentTemperatureResolver.resolve`, when calculating a *new* delta from an observation, compare the observation temperature against the forecast interpolated at the **observation's own timestamp**, not the current fetch time.
- Displayed Temp = `Forecast(now) + DecayedDelta`
- Delta = `Observation(obsTime) - Forecast(obsTime)`

### 3. Database Logging for Forensics
Add more granular logging to the `app_logs` table (tag: `TEMP_RESOLVE`) specifically recording:
- The exact `now` timestamp used.
- The `estimatedTemp` before delta.
- The `appliedDelta` and its decay fraction.
- The `observedAt` time vs `now` time.

## Implementation Plan

### Phase 1: Data Model & State Updates
- Update `CurrentTemperatureDeltaState` to include `sourceId` (already there, but ensure it's used correctly).
- Modify `WidgetStateManager` to store deltas in a map-like structure in SharedPreferences (e.g., `widget_current_temp_delta_NWS_{id}`) instead of a single global slot per widget.
- Remove `clearCurrentTempDeltaState` calls from `toggleDisplaySource`.

### Phase 2: Resolver Logic Alignment
- Modify `CurrentTemperatureResolver.resolve` to accept an optional `observedAt` timestamp.
- If `observedAt` is provided and a new delta is being created, use `observedAt` to calculate the `estimatedTemp` for the delta baseline.
- Ensure `getDecayedDelta` is using the delta anchor correctly.

### Phase 3: Handler Synchronization
- Ensure `now` is passed or calculated consistently.
- Verify `ObservationResolver.resolveObservedCurrentTemp` is returning the same object/timestamp to all handlers.

## Verification Plan

### Automated Tests
- **CurrentTemperatureResolverTest**: Add a test case where a delta is calculated for an observation from 30 minutes ago, and verify it matches the forecast at that 30-minute mark.
- **WidgetStateManagerTest**: Verify that toggling from NWS -> Open-Meteo -> NWS preserves the NWS delta.

### Manual Verification
- Deploy to emulator.
- Observe current temp in Daily View (NWS).
- Toggle through all APIs back to NWS.
- Verify temperature remains consistent (accounting for natural decay).
- Click temp to switch to Hourly view.
- Verify header temperature does not jump.

## Migration & Rollback
- SharedPreferences keys will be versioned or namespaced by source. Old keys will be ignored/cleaned up.
- Rollback involves reverting to the single-key storage logic and re-enabling `clearCurrentTempDeltaState`.
