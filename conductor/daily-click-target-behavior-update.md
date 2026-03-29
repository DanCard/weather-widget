# Implementation Plan - Split Daily Forecast Click Behavior

The goal of this task is to modify the click behavior in the daily forecast view so that clicking the main column area always leads to the Temperature graph (unless it's a rainy day), while clicking the icon area (bottom row) specifically leads to the "home" graph for that condition (e.g., Cloud Cover for cloudy days).

## Objective
Update the `DayClickHelper` logic to distinguish between a "general" day click (column) and a "condition-specific" click (icon/bottom row), and add an integration test to verify this behavior.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`**: Contains the pure logic for resolving target view modes.
- **`app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerTest.kt`**: Contains tests for the daily view handler, including click intent verification.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`**: Uses the helper functions to set up click listeners (already distinguishes between column and bottom row zones).
- **`app/src/androidTest/java/com/weatherwidget/widget/handlers/DailyMainColumnVsBottomIconClickTargetIntegrationTest.kt`**: (New) Instrumented integration test to verify the end-to-end click behavior on a device/emulator.

## Implementation Steps

### 1. Update `DayClickHelper.kt`
Modify the resolution logic to separate the column behavior from the icon behavior.

-   Update `resolveDailyTargetViewMode` to return `PRECIPITATION` only for rainy icons, defaulting all others (including cloudy ones) to `TEMPERATURE`.
-   Keep `resolveBottomRowTargetViewMode` calling `resolveIconHome` (which returns `CLOUD_COVER` for cloudy days).

### 2. Update `DailyViewHandlerTest.kt`
Adjust the unit test expectations.

-   Update `buildDayClickIntent tomorrow cloudy icon navigates to cloud cover` to expect `TEMPERATURE` instead of `CLOUD_COVER`.

### 3. Create Integration Test
Create `app/src/androidTest/java/com/weatherwidget/widget/handlers/DailyMainColumnVsBottomIconClickTargetIntegrationTest.kt`.

-   Extend `IsolatedIntegrationTest`.
-   Set up a mock environment with a cloudy forecast for today.
-   Render the Daily view with enough height to trigger Graph Mode.
-   Test 1: `clickingMainColumnBody_onCloudyDay_navigatesToTemperatureMode()`: Click `R.id.graph_day2_zone` and verify `stateManager.getViewMode` is `TEMPERATURE`.
-   Test 2: `clickingBottomIconZone_onCloudyDay_navigatesToCloudCoverMode()`: Click `R.id.graph_bottom_day2_zone` and verify `stateManager.getViewMode` is `CLOUD_COVER`.

## Verification & Testing

### Unit Tests
-   Run `DailyViewHandlerTest` and `DailyViewHandlerIntentContractTest`.
-   Command: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewHandler*"`

### Integration Tests
-   Run the new `DailyMainColumnVsBottomIconClickTargetIntegrationTest`.
-   Command: `./scripts/emulator-tests.sh app/src/androidTest/java/com/weatherwidget/widget/handlers/DailyMainColumnVsBottomIconClickTargetIntegrationTest.kt`
-   Verify all tests in `DayClickNavigationTest.kt` also pass.
