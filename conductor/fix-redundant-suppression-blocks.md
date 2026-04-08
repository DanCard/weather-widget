# Refactoring Plan: Deduplicate Temperature Label Suppression Blocks (#11)

Extract redundant label suppression logic in `TemperatureGraphRenderer.kt` into a single parameterized helper function.

## Proposed Changes

### 1. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

-   Add a private helper function `isRedundantNear` to encapsulate the logic for checking if a label is redundant near another label based on distance and value difference.
-   Refactor the loop in `collectLabelCandidates` to use this helper function instead of the six near-identical `if` blocks.

#### New Helper Function:
```kotlin
    /**
     * Checks if a label at [idx] with [role] is redundant because it's too close to [targetIdx]
     * (which has a different role) and their temperature values are nearly identical.
     */
    private fun isRedundantNear(
        idx: Int,
        role: TemperatureRole,
        targetIdx: Int,
        suppressedIndices: Set<Int>,
        currentVal: Float,
        targetVal: Float,
        window: Int,
        threshold: Float,
        reasonSuffix: String
    ): Boolean {
        if (targetIdx >= 0 && targetIdx !in suppressedIndices && abs(idx - targetIdx) <= window) {
            if (abs(currentVal - targetVal) < threshold) {
                Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_$reasonSuffix dist=${abs(idx - targetIdx)} valueDiff=${abs(currentVal - targetVal)}")
                return true
            }
        }
        return false
    }
```

#### Refactored Logic in `collectLabelCandidates`:
```kotlin
            val redundantPairWindow = min(8, hours.lastIndex / 5)
            val redundantValueThreshold = 2f
            
            val isRedundant = when (role) {
                TemperatureRole.ACTUAL_HIGH -> isRedundantNear(idx, role, extrema.dailyHighIndex, suppressedIndices, actualLabelTemps[idx], labelTemps[extrema.dailyHighIndex], redundantPairWindow, redundantValueThreshold, "HIGH")
                TemperatureRole.ACTUAL_LOW -> isRedundantNear(idx, role, extrema.dailyLowIndex, suppressedIndices, actualLabelTemps[idx], labelTemps[extrema.dailyLowIndex], redundantPairWindow, redundantValueThreshold, "LOW")
                TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH -> isRedundantNear(idx, role, extrema.actualHighIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualHighIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_HIGH")
                TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW -> isRedundantNear(idx, role, extrema.actualLowIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualLowIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_LOW")
                else -> false
            }

            if (isRedundant) {
                suppressedIndices.add(idx)
                continue
            }
```

## Verification Plan

### Automated Tests
-   Run existing widget tests to ensure no regressions in label placement.
    -   `./gradlew test`
    -   Specifically check tests related to `TemperatureGraphRenderer`.

### Manual Verification
-   Visual inspection of the widget on the emulator to confirm that labels are still correctly deduplicated (e.g., when Actual High and Forecast High are very close).
-   Verify logs for `LABEL_CANDIDATE_SKIPPED` with `REDUNDANT_NEAR_*` reasons.
