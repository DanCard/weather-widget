# Task Plan - Fix Stretched Graphs on Pixel 7 Pro

## Goal
Diagnose and fix the issue where daily and hourly graphs appear vertically stretched on Pixel 7 Pro, while appearing correct on other devices and emulators.

## Phases
- [ ] Phase 1: Code Investigation <!-- id: 1 --> [COMPLETE]
    - Locate graph rendering logic.
    - Analyze height and scaling calculations.
    - Check for DP/PX conversion issues.
- [ ] Phase 2: Root Cause Analysis <!-- id: 2 --> [COMPLETE]
    - Determine why Pixel 7 Pro specifically exhibits this behavior.
    - Compare dimensions/density with other devices.
- [ ] Phase 3: Solution Proposal <!-- id: 3 -->
    - Design a fix for consistent graph scaling.
    - Proposal: 
        1. Implement `MIN_TEMP_RANGE = 10f`.
        2. Cap `graphHeight` to maintain a reasonable aspect ratio (e.g., max 0.8 * width).
        3. Center the graph within the available space.
    - Present plan to user for consent.
- [ ] Phase 4: Implementation <!-- id: 4 -->
    - Apply the fix to `TemperatureGraphRenderer.kt`.
    - Apply the fix to `HourlyGraphRenderer.kt`.
- [ ] Phase 5: Verification <!-- id: 5 -->
    - Verify with tests if possible.
    - Check for regressions on other simulated sizes.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| | | |
