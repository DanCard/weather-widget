# Plan - Fix Temperature Discrepancy (Header vs Graph)

## Objective
Ensure the widget header's current temperature (top left) and the hourly graph's last observed temperature are derived from the same data and logic, with the only difference being that the header temperature extrapolates forward to the current time.

## Background & Root Causes
1.  **Narrow Zoom Window**: `resolveGraphStyleCurrentTemp` uses the graph's zoom window to fetch observations. In "Narrow" zoom, it might look back only 2 hours. If the last observation is 3 hours old, it returns null and falls back to a different, less accurate path.
2.  **Double-Counting of Trend**: `resolveGraphStyleCurrentTemp` returns an extrapolated temperature for "now" but labels it with the *anchor time* (original observation time). `CurrentTemperatureResolver.resolve` receives this and extrapolates it *again*, causing a discrepancy.

## Proposed Solution

### Phase 1: Broaden `resolveGraphStyleCurrentTemp` Window (Priority)
Modify `WidgetIntentRouter.resolveGraphStyleCurrentTemp` to ignore the zoom level when fetching observations and always use a fixed, generous lookback window (e.g., 12 hours). This ensures the header can always find the same latest observation that the graph is showing, regardless of the current zoom.

### Phase 2: Consolidate Data & Logic
Ensure `resolveGraphStyleCurrentTemp` and the hourly graph rendering both use the same `ObservationBlender` results. 
-   **Modify `resolveGraphStyleCurrentTemp`**: Instead of returning an already-extrapolated temperature for "now", it should return the **real anchor observation** (the most recent blended observation it found). 
-   **Header Extrapolation**: Let `CurrentTemperatureResolver.resolve` perform the forward extrapolation to "now". This ensures that the header's extrapolation logic is consistent with how it handles all other sources.

### Phase 3: Fix `ObservationResolver.resolveObservedCurrentTemp` (Fallback)
Update the fallback logic to correctly pick the newest observation by timestamp, removing any stale preference for `NWS_BLEND`.

---

## Implementation Plan

### Step 1: Broaden Lookback Window
- [ ] **`WidgetIntentRouter.kt`**: In `resolveGraphStyleCurrentTemp`, change the `startHour` calculation to use a fixed 12-hour lookback instead of `zoom.backHours`.

### Step 2: Fix Data Consistency (Anchor vs Extrapolated)
- [ ] **`ObservationBlender.kt`**: Update `resolveCurrentObservation` (or add a new variant) to return the *anchor temperature* (the real, blended observation value at the anchor timestamp) instead of the forecast-extrapolated one for "now".
- [ ] **`WidgetIntentRouter.kt`**: Update `resolveGraphStyleCurrentTemp` to return this anchor temperature. This ensures the header receives the exact same "latest observation" value that the graph is showing.

### Step 3: Fix Fallback Logic
- [ ] **`ObservationResolver.kt`**: Update `resolveObservedCurrentTemp` to prioritize the newest timestamp over the `NWS_BLEND` station ID.

---

## Verification & Testing
- Manual verification on emulator:
    1.  Observe the temperature dot in the hourly graph (e.g., 61.2).
    2.  Check the header temperature. It should be 61.2 if it's currently at the same time as the observation, or slightly different if it has extrapolated forward.
    3.  Verify that zooming in/out does not cause the header temperature to change or fall back to a different value.
- Run `CurrentTemperatureResolverTest.kt` and `ObservationResolverTest.kt`.
