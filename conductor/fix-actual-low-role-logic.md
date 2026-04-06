# Fix Actual Low Role Logic Plan

## Objective
Ensure `ACTUAL_LOW` and `ACTUAL_HIGH` roles introduced in a recent change are correctly classified as valleys/peaks and essential labels so they get placed below the curve and prioritize visibility.

## Changes

1. **Update `isPeak` check:**
   In `TemperatureGraphRenderer.kt` (around line 802), update the check to include `ACTUAL_HIGH`:
   `val isPeak = it.role in listOf("HIGH", "FORECAST_HIGH", "ACTUAL_HIGH") || (it.role == "LOCAL" && it.rawTemperature > leftVal && it.rawTemperature > rightVal)`

2. **Update `isValley` check:**
   In `TemperatureGraphRenderer.kt` (around line 824), update the check to include `ACTUAL_LOW`:
   `val isValley = candidate.role in listOf("LOW", "FORECAST_LOW", "ACTUAL_LOW") || (candidate.role == "LOCAL" && temps[idx] < leftVal && temps[idx] < rightVal)`

3. **Update `isEssential` check:**
   In `TemperatureGraphRenderer.kt` (around line 825), update the check to include `ACTUAL_LOW` and `ACTUAL_HIGH`:
   `val isEssential = candidate.role in setOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "ACTUAL_LOW", "ACTUAL_HIGH", "START", "END", "ACTUAL_END")`
   (Note: Use `setOf` for performance if it was a list).

## Verification
- Unit tests: Run widget test suite.
- Emulator logs: Verify `LABEL_PLACED` for `ACTUAL_LOW` correctly states `placement=below` and respects the updated padding/overlap settings.
