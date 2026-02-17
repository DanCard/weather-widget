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
The user reports the label is still missing on the Samsung device. Since `Log.d` is not persisted to the DB, we cannot see *why* it was skipped (e.g., collision vs not detected).

1.  **Instrument Logging**: Add temporary `AppLogDao` logging to `HourlyTemperatureGraphRenderer` to record:
    -   Calculated `smoothedTemps`
    -   Identified `localExtrema` indices
    -   For each label: position (x,y), bounds, and collision result (DRAWN/SKIPPED).
2.  **Deploy & Capture**: Install on Samsung, let it render, then pull the DB.
3.  **Analyze**: Determine if the label was:
    -   Not found in `localExtrema` (logic issue with specific data?)
    -   Found but skipped due to collision (with what?)
    -   Found and "drawn" but off-screen?

## Implementation Plan Phase 2
- [ ] Add temporary DB logging to Renderer
- [ ] Pull logs from Samsung device
- [ ] Fix root cause (likely collision with Start/End or existing label)
- [ ] Remove temporary logging
