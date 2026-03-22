# Architectural Analysis: Delta Decay Complexity

## Overview
The "Delta Decay" feature was implemented to provide a smooth mathematical bridge between a local weather station observation (usually 15-45 minutes old) and the real-time interpolated forecast. It linearly reduces the difference (delta) between the two toward zero over a 4-hour window.

## The Complexity Problem
While intended to prevent sudden "jumps" in temperature, the feature introduces significant architectural and logical costs:

1.  **Logical Fragility (Synthetic Trends)**:
    - As a delta approachs zero, it effectively adds/removes "synthetic" heat or cold to the display.
    - If a station is cooler than the forecast (negative delta), the decay process makes the displayed temperature "rise" toward the forecast, even if the actual weather trend is downward. This results in counter-intuitive UI behavior.

2.  **Persistence Overhead**:
    - Requires saving `CurrentTemperatureDeltaState` to disk (SharedPrefs) including `delta`, `lastObservedAt`, `locationLat`, `locationLon`, and `sourceId`.
    - This state must be reloaded and validated on every single widget update, including lightweight UI-only refreshes.

3.  **Code Maintenance**:
    - The `CurrentTemperatureResolver` must perform time-based math and handle edge cases (location changes, API source changes) on every render.
    - Testing requires complex time-mocking to verify linear decay slopes.

## Proposed Simpler Models

### Model A: Fixed Delta (Recommended)
- **Logic**: `CurrentTemp = CurrentForecast + LastKnownDelta`.
- **Behavior**: The delta remains constant until a new observation is fetched.
- **Pros**: Perfectly follows the forecast trend (no synthetic movements). Respects local micro-climates (the "airport is always 2 degrees warmer than my house" effect).
- **Cons**: Small potential "jump" when a new observation arrives if the delta has changed significantly.

### Model B: The Hard Switch
- **Logic**: Use station observation if < 90 minutes old; otherwise, use the interpolated forecast directly.
- **Behavior**: Zero hidden math or state.
- **Pros**: Maximum transparency for the user.
- **Cons**: Visible jump at the 90-minute mark when the observation expires.

## Conclusion
Delta decay provides a "gentle" mathematical transition at the cost of meteorological accuracy and code simplicity. Moving to a **Fixed Delta (Model A)** would significantly simplify the codebase, reduce state-management overhead, and ensure that the displayed temperature always moves in lock-step with the forecast trend.
