# Plan: High Battery Fast Fetch

## Objective
Update the `ForecastFetchPolicy` to treat devices with a battery level of 80% or higher as if they are actively charging. This will allow emulators (which often report 100% battery but `isCharging=false`) and real devices with healthy batteries to bypass the slower `BatteryFetchStrategy` and instead use the more aggressive charging fetch intervals.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt`: Contains the pure-decision functions for `intervalMinutes` and `periodicTickMinutes`.

## Implementation Steps

1.  **Modify `intervalMinutes` in `ForecastFetchPolicy.kt`**:
    *   Introduce a local variable: `val treatAsCharging = isCharging || batteryLevel >= 80`.
    *   Change the early exit condition from `if (!isCharging)` to `if (!treatAsCharging)`.

2.  **Modify `periodicTickMinutes` in `ForecastFetchPolicy.kt`**:
    *   Introduce the same local variable: `val treatAsCharging = isCharging || batteryLevel >= 80`.
    *   Change the early exit condition from `if (isCharging)` to `if (treatAsCharging)`.

## Verification & Testing
1.  Run the existing unit tests for `ForecastFetchPolicy` to ensure they still pass.
2.  Add a new unit test in `ForecastFetchPolicyTest` asserting that when `isCharging=false` and `batteryLevel=80`, the resulting `intervalMinutes` aligns with the charging matrix (e.g., 60 or 120 minutes) rather than the `BatteryFetchStrategy` fallback (which would normally be 240 minutes for battery > 70).
3.  Deploy to the `emulator-5556` device. Ensure that it successfully executes the aggressive periodic refresh loop instead of falling into the 4-hour delay loop.