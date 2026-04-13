# Objective
Fix the issue where local peak labels (extrema) and the end-of-graph label are missing from the hourly temperature graph, even after the sorting priority fix.

# Key Findings
1.  **High Prominence Threshold**: `MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES` is currently `2.5f`, causing many valid peaks (e.g., a 1-degree bump) to be rejected by `TemperatureExtrema.compute`.
2.  **Missing "Essential" Flag for Local Extrema**: `LOCAL` roles are not in the `isEssential` set in `TemperatureLabelResolver.resolveCandidatePlacement`. This means if a peak collides with an icon, it is silently dropped instead of being "forced" with a leader line or minor overlap.
3.  **Low Filtering Priority for Edge Labels**: In `GraphLabelPlacementUtils.candidatePriority`, `CandidateKind.EDGE` has the lowest priority (4). This causes the `END` label to be filtered out if it is within 4 hours of any other label (like a nearby peak or the daily high).

# Implementation Steps

1.  **Update `TemperatureLabelResolver.kt`**
    *   Lower `MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES` from `2.5f` to `1.0f`.
    *   Update `resolveCandidatePlacement` to include `TemperatureRole.LOCAL` in the `isEssential` set.

2.  **Update `GraphLabelPlacementUtils.kt`**
    *   Increase priority of `CandidateKind.EDGE` from 4 to 1 (same as peaks) to prevent them from being easily filtered out by nearby candidates.

3.  **Update Unit Test `TemperatureLabelResolverSortTest.kt`**
    *   Add a test case ensuring that `LOCAL` extrema are considered essential.

# Verification & Testing
- Run `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureLabelResolverSortTest"`
- Build and deploy to the emulator.
- Observe the hourly temperature graph and verify that local peaks are now labeled even with minor bumps.
- Verify that the `END` label appears even when a peak is nearby.