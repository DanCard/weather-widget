# Objective
Fix the issue where local peak labels (extrema) and the end-of-graph label are frequently missing from the hourly temperature graph due to priority sorting errors.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureLabelResolver.kt`
  - The `sortLabelCandidates` function currently sorts labels primarily by their temperature value (`displayTemp`), and secondarily by their `TemperatureRole` priority. Because temperatures are rarely identical, the role priority is effectively ignored. This causes lower-priority labels to be drawn before higher-priority `LOCAL` extrema or forces endpoints to collide with non-essential labels, leading to missing labels.
- `app/src/test/java/com/weatherwidget/widget/TemperatureLabelResolverSortTest.kt`
  - A new test file to guarantee that role priority is respected over temperature values.

# Implementation Steps
1. **Update `TemperatureLabelResolver.kt`**
   - Modify `sortLabelCandidates` to sort by `TemperatureRole` priority FIRST, and then by temperature value.
   - This ensures that essential global extrema and local peaks are placed on the graph before endpoints, guaranteeing the most critical data is rendered without being overridden by less informative labels.

```kotlin
    fun sortLabelCandidates(candidates: MutableList<TempLabelCandidate>) {
        candidates.sortWith(
            compareBy<TempLabelCandidate> {
                when (it.role) {
                    TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW -> 0
                    TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> 1
                    else -> 2 // START, END
                }
            }.thenBy {
                val displayTemp = it.labelTemps[it.index]
                val leftVal = findPrevDifferent(it.labelTemps, it.index)
                val rightVal = findNextDifferent(it.labelTemps, it.index)
                val isPeak = it.role in listOf(TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.PAST_FORECAST_HIGH) || (it.role == TemperatureRole.LOCAL && displayTemp > leftVal && displayTemp > rightVal)
                if (isPeak) -displayTemp else displayTemp
            }
        )
    }
```

2. **Add Unit Test `TemperatureLabelResolverSortTest.kt`**
   - Create a test verifying that a `LOCAL` role is sorted before an `END` role, regardless of their temperature values. 
   - Create a test verifying that a `HIGH` role is sorted before both `LOCAL` and `END`.

# Verification & Testing
- Run `./gradlew test --tests *TemperatureLabelResolverSortTest*` to verify the automated test passes.
- Build and deploy to the emulator.
- Observe the hourly temperature graph and verify that `LOCAL` peaks and valleys are consistently labeled and take precedence.
- Verify that the `END` label appears correctly when space permits.