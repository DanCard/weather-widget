# Plan - Fix Failing Temperature Graph Tests

The unit tests for `TemperatureGraphLabelPlacementRobolectricTest` are currently failing in the `app` module due to mismatched indices and incorrect assertions that do not align with the current implementation of `TemperatureGraphRenderer`. A correct version of the test file exists in the `conductor` directory.

## Objective
Fix the test failures by syncing `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt` with the verified version in `conductor/TemperatureGraphLabelPlacementRobolectricTest.kt`.

## Key Files & Context
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt`: The failing test file.
- `conductor/TemperatureGraphLabelPlacementRobolectricTest.kt`: The source of truth for the fixed tests.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: The renderer being tested, confirming the logic in the fixed tests.

## Implementation Steps
1. **Sync Test File**: Overwrite the content of `app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt` with the content from `conductor/TemperatureGraphLabelPlacementRobolectricTest.kt`.
2. **Verify Fix**: Run the `:app:testMediumDebugUnitTestFresh` task again to ensure all tests pass.

## Verification & Testing
- Run `./gradlew :app:testMediumDebugUnitTestFresh --tests TemperatureGraphLabelPlacementRobolectricTest` to specifically verify the fix.
- Ensure no other regressions in the test suite.
