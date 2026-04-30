# Plan: Fix Test Failures and Compilation Error

This plan addresses the current test failures and the compilation error introduced during the implementation of the "Shift Night Rain Labels" plan.

## Background
- The project is shifting to a new precipitation probability threshold formula: `(7.0 / 3.0 * daysFromToday + 16)`.
- The source code in `DailyForecastIconResolver.kt` and `WeatherIconMapper.kt` was updated to reflect this, but some tests were still expecting the old values (based on a `+ 10` formula).
- A compilation error was introduced in `DailyViewLogic.kt` where the `probability` variable was removed but still used.

## Proposed Changes

### 1. Fix Compilation Error
- **File**: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- **Change**: Re-introduce the `probability` variable assignment in `buildNightRainLabel` or use `nightPrecipProbability` directly. I will re-introduce it for better readability and logging as originally intended.

### 2. Standardize Thresholds
- **File**: `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`
- **Ensure**: `getMinimumPrecipProbabilityDay` uses `+ 16`. (Currently on disk as `+ 16`).
- **File**: `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`
- **Ensure**: `getPrecipitationIcon` uses `precipProbability < 16`. (Currently on disk as `16`).

### 3. Update Test Expectations
- **File**: `app/src/test/java/com/weatherwidget/util/DailyForecastIconResolverTest.kt`
- **Change**: Update assertions to match the `+ 16` formula.
- **File**: `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- **Change**: Update assertions to match the `+ 16` formula (e.g., change `19` to `17` for Day 1 suppression tests).

## Verification
- Run `:app:testShortDebugUnitTestFresh` (Verify `DailyForecastIconResolverTest` and `WeatherIconMapperTest`).
- Run `:app:testMediumDebugUnitTestFresh` (Verify `DailyViewLogicTest`).
- Ensure all tests pass and the build is stable.
