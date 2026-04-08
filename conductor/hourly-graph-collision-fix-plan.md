# Fix Hourly Graph Label Collisions Stacking Order

## Objective
Fix visual stacking of overlapping labels on the hourly temperature graph so the lowest temperature label is on the bottom and the highest temperature label is on top.

## Background & Motivation
When two labels collide below the curve (e.g., an `ACTUAL_LOW` and a `FORECAST_LOW`), the one drawn second is pushed further down by the `MAX_LEADER_DISPLACEMENT_STEPS` logic. Currently, `specialCandidates` are sorted primarily by their role (priority 0 for `FORECAST_LOW`, priority 2 for `ACTUAL_LOW`), and secondarily by temperature extremeness (`-it.rawTemperature` for peaks, `it.rawTemperature` for valleys). 

This causes:
1. Lower priority roles (like `ACTUAL_LOW`) to be drawn *after* higher priority roles (`FORECAST_LOW`), pushing them further outwards regardless of their actual temperatures.
2. Even within the same priority, the *most extreme* temperatures are drawn first (closest to the curve), pushing less extreme temperatures outwards, resulting in an inverted physical layout (e.g., 50° being drawn visually lower than 48°).

## Proposed Solution
Modify the candidate sorting logic in `TemperatureGraphRenderer.kt` to make temperature the **primary** sort key, and invert the direction so that *less extreme* temperatures are placed first (closer to the curve). This guarantees that more extreme temperatures are pushed outwards (further up or down) during a collision, maintaining a correct physical layout.

## Implementation Steps

1. **Update `TemperatureGraphRenderer.kt` Sorting:**
   Replace the `specialCandidates.sortWith` block (around line 963) with the following logic:
   ```kotlin
        specialCandidates.sortWith(
            compareBy<TempLabelCandidate> {
                val leftVal = it.labelTemps.subList(0, it.index).findLast { v -> v != it.rawTemperature } ?: it.rawTemperature
                val rightVal = it.labelTemps.subList(it.index + 1, it.labelTemps.size).find { v -> v != it.rawTemperature } ?: it.rawTemperature
                val isPeak = it.role in listOf("HIGH", "FORECAST_HIGH", "ACTUAL_HIGH", "PAST_FORECAST_HIGH") || (it.role == "LOCAL" && it.rawTemperature > leftVal && it.rawTemperature > rightVal)
                // Primary Sort: Temperature.
                // Peaks (above curve): sort ascending (lower temps placed first, higher temps pushed up)
                // Valleys (below curve): sort descending (higher temps placed first, lower temps pushed down)
                if (isPeak) it.rawTemperature else -it.rawTemperature
            }.thenBy {
                // Secondary Sort: Role Priority
                when (it.role) {
                    "HIGH", "LOW", "FORECAST_HIGH", "FORECAST_LOW", "PAST_FORECAST_LOW", "PAST_FORECAST_HIGH", "ACTUAL_HIGH", "ACTUAL_LOW" -> 0
                    "LOCAL", "ACTUAL_END" -> 1
                    else -> 2 // START, END
                }
            }
        )
   ```

2. **Add `ACTUAL_HIGH` and `ACTUAL_LOW` to Priority 0:**
   As shown above, ensure `ACTUAL_HIGH` and `ACTUAL_LOW` are included in the `0` priority bucket so they are treated equally to their forecast counterparts when temperatures are tied.

## Verification & Testing
1. **Update Automated Tests**: Add a new test to `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt` named `test colliding labels stack in correct temperature order`.
   - **Setup**: Create a scenario with an `ACTUAL_LOW` and a `FORECAST_LOW` that are horizontally close enough to collide, but have different temperatures (e.g., 50° and 48°). Make sure they are at the bottom of the graph so they are forced to displace downwards.
   - **Assertion**: Extract the `LabelPlacementDebug` for both labels. Assert that the label with the lower temperature has a *larger* `y` value (which means it is physically lower on the screen). Repeat the scenario for peaks (e.g., `ACTUAL_HIGH` vs `FORECAST_HIGH`) asserting the higher temperature has a *smaller* `y` value (physically higher).
2. Run `./gradlew test` to ensure all unit tests related to `TemperatureGraphRenderer` pass.
3. Run `./scripts/emulator-tests.sh` to verify no regressions in visual structure and verify overlapping ACTUAL/FORECAST valley and peak labels stack correctly.
