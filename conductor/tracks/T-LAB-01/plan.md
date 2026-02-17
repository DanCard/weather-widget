# Track T-LAB-01: Fix missing 6am min temperature label

## Problem Description
The hourly temperature graph is missing a label for a local minimum occurring around 6 am. Other labels appear to be working, but this specific point is being skipped or filtered out.

## Investigation Strategy (Evidence-First)
1.  **Database Evidence**: Query `hourly_forecasts` for the relevant time window to see the raw values.
    -   *Findings*: The 6am value (44.0) is NOT the global minimum for the day. The temperature drops to 43.0 at 10pm and 42.0 at midnight.
    -   *Conclusion*: The current renderer ONLY labels Global High/Low. The 6am dip is a *local* minimum, which is why it's ignored.
2.  **Log Evidence**: Check `app_logs` for "HourlyGraph" tags to see which labels were calculated and if any were filtered for overlap/proximity.
3.  **Code Analysis**: Examine `HourlyTemperatureGraphRenderer.kt` logic for `dailyLowIndex` and `specialIndices`.

## Implementation Plan
- [x] Investigate DB and Logs
- [x] Reproduce issue (confirmed: code design limitation)
- [x] **Modify `HourlyTemperatureGraphRenderer.kt` to label local extrema:**
    -   Implement logic to find significant local peaks/valleys (not just global).
    -   Add these to `specialIndices` with secondary priority.
    -   Use existing collision detection to prevent clutter.
- [x] Verify fix (Unit test `LocalExtremaTest.kt` confirms detection of the 6am dip)

## Investigation Phase 2 (Issue Persists)
- [x] Add temporary DB logging to Renderer
- [x] Pull logs from Samsung device
- [x] **Analyze**:
    -   Logs show `SKIPPED OTHER idx=3`.
    -   Collision with `DRAWN START idx=0`.
    -   Root Cause: Start label (priority 3) overlaps with Local Min (priority 5).
- [x] **Fix**: Change priority order. Draw Local Extrema *before* Start/End labels.
    -   New Order: Global Low -> Global High -> Local Extrema -> Start -> End.
- [x] Remove temporary logging.
- [ ] Verify fix.

## Implementation Plan Phase 2
- [ ] Adjust priority in `HourlyTemperatureGraphRenderer.kt`
- [ ] Remove debug logging
- [ ] Verify on device
