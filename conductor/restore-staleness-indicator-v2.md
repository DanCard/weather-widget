# Plan: Fix Pessimistic Staleness Indicator on Hourly Graph

## Objective
The staleness indicator (fetch dot and age text) on the hourly temperature graph is sometimes "overly pessimistic," showing data is older than it actually is. This happens when a nearby station (the "anchor" for a graph slot) is lagging and enters an "extrapolated" state, even if other slightly further stations have fresh "observed" data for that same time.

The fix is to prioritize the `"observed"` status in the blended graph points if any constituent station provides a real observation.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**: Contains the `blendObservationSeries` logic that assigns the `condition` (source status) to blended points.
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**: Uses the `isObservedActual` flag (derived from `condition == "observed"`) to position the fetch dot.

## Implementation Steps

### 1. Update Blending Logic
In `TemperatureViewHandler.blendObservationSeries`, change how the result's `condition` is determined. Instead of just using `anchor.sourceKind`, aggregate the status from all `peers` in the cohort.

**Proposed Logic:**
```kotlin
val bestSourceKind = when {
    peers.any { it.sourceKind == "observed" } -> "observed"
    peers.any { it.sourceKind == "interpolated" } -> "interpolated"
    else -> "forecast_extrapolated"
}
```

### 2. Update Tests
Add a test case to `TemperatureViewHandlerActualsTest.kt` that simulates this exact scenario:
- Two stations: one close (lagging/extrapolated), one further (fresh/observed).
- Verify that the blended result at the current time is marked as `"observed"`.

## Verification & Testing

### Unit Tests
Run `./gradlew test` specifically for `TemperatureViewHandlerActualsTest`.

### Manual Verification
1. Open the hourly temperature graph on a device with multiple stations nearby (e.g., San Jose area in the logs).
2. Observe if the staleness text ("Xm") matches the freshest available station in the "Current Observations" list, even if the closest station is lagging.
