# Plan - Declutter Precipitation and Cloud Cover Graph Labels

Address visual clutter in hourly precipitation and cloud cover graphs where multiple similar labels (e.g., 10%, 9%, 9%) appear too close to each other. We will implement decluttering rules that apply even when the total number of labels is below the maximum cap, and unify local extrema detection logic.

## Objective
-   **Eliminate redundant labels**: Ensure that if multiple similar values are clustered (within 3 hours), only the most significant one is shown.
-   **Unify Extremas Logic**: Extract plateau-aware local extrema detection into a shared utility and use it for both Precipitation and Cloud Cover graphs.
-   **Mandatory Decluttering**: Update the filtering algorithm to always perform a "decluttering pass" with a small threshold, regardless of whether the `maxCandidates` cap has been reached.

## Key Files & Context
-   `app/src/main/java/com/weatherwidget/widget/GraphLabelPlacementUtils.kt`: Contains the filtering logic.
-   `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt`: Shared utilities for graph rendering.
-   `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`: Uses the filtering logic.
-   `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Uses the filtering logic and currently has non-plateau-aware extrema detection.

## Implementation Steps

### 1. Extract Shared Extrema Detection
Move `findLocalExtremaIndices` from `PrecipitationGraphRenderer.kt` to `GraphRenderUtils.kt` (or `GraphLabelPlacementUtils.kt`).

```kotlin
// In GraphRenderUtils.kt or GraphLabelPlacementUtils.kt
fun findLocalExtremaIndices(
    values: List<Int>,
    isMax: Boolean,
): Set<Int> {
    if (values.size < 3) return emptySet()
    val extrema = mutableSetOf<Int>()
    var i = 1
    while (i < values.lastIndex) {
        val current = values[i]
        val prev = values[i - 1]
        val isPotential = if (isMax) current > prev else current < prev
        if (isPotential) {
            var j = i
            while (j < values.lastIndex && values[j + 1] == current) j++
            if (j < values.lastIndex) {
                val next = values[j + 1]
                val isExtremum = if (isMax) next < current else next > current
                if (isExtremum) extrema.add(i + (j - i) / 2)
            }
            i = j
        }
        i++
    }
    return extrema
}
```

### 2. Update Cloud Cover Extrema Logic
Refactor `CloudCoverGraphRenderer.renderGraph` to use the shared `findLocalExtremaIndices` function. This will improve its awareness of plateaus (flat peaks/valleys).

### 3. Update Mandatory Decluttering Logic
Modify `GraphLabelPlacementUtils.filterDenseLabelCandidates` to always run at least one pass with a small threshold (e.g., 3%) to remove very close and similar labels, even if the total count is already under `maxCandidates`.

```kotlin
// In GraphLabelPlacementUtils.kt
fun <T> filterDenseLabelCandidates(...) {
    // 1. Remove the early return: if (candidates.size <= maxCandidates) return candidates.sorted()
    
    // 2. Add a mandatory decluttering threshold if not already present
    val effectiveThresholds = if (diffThresholds.firstOrNull() ?: 0 > 3) {
        listOf(3) + diffThresholds
    } else {
        diffThresholds
    }

    for (threshold in effectiveThresholds) {
        // If we are already under the cap AND this is a "reduction" threshold (not a decluttering one), break.
        if (retained.size <= maxCandidates && threshold > 3) break
        
        // ... existing filtering logic ...
    }
}
```

### 4. Review Protected Indices
Ensure `firstPositive` in `PrecipitationGraphRenderer.kt` is only protected if it's significantly different from nearby extrema, or consider allowing its removal if a stronger valley/peak is nearby. For now, we will keep it protected but the mandatory decluttering will remove the *other* redundant 9% labels.

## Verification & Testing

### Automated Tests
-   **Run Repro Test**: Execute `app/src/test/java/com/weatherwidget/widget/PrecipitationClutterReproTest.kt` (updated with assertions) to verify that 10%, 9%, 9% is reduced to 10%, 9% or just one of them depending on strength.
-   **Cloud Cover Plateau Test**: Add a test case to `CloudCoverGraphRendererTest.kt` that specifically checks for plateau detection (e.g., `[50, 80, 80, 80, 50]` should label one 80%).

### Manual Verification
-   **Emulator Check**: Observe the Precipitation and Cloud Cover graphs in the emulator.
-   **Scenario**: Find a period with light precipitation (e.g., 10%, 9%, 9%) and verify only one or two clear labels are shown instead of a cluster.
