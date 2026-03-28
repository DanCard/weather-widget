# Plan: Enhance Automated Testing for Daily Forecast View

## Background & Motivation
While we have added unit tests for the core logic of `DailyViewLogic.prepareGraphDays`, the Daily Forecast view is a complex orchestration of several components: `DailyViewLogic`, `DailyViewHandler`, `NavigationUtils`, and `DailyForecastGraphRenderer`. To ensure the fix for missing columns and climate fallback is robust and doesn't regress, we need a more comprehensive automated test plan.

## Proposed Solution
1.  **Add Unit Tests for `prepareTextDays`**: The fallback logic was also applied to Text Mode, but it is currently untested.
2.  **Add Unit Tests for `NavigationUtils.getDayOffsets`**: Verify that the correct number of offsets is generated for various widget widths.
3.  **Create an Automated Test Plan Document**: Define the scope of automated vs. manual verification.

### Implementation Steps

#### 1. Update `DailyViewLogicTest.kt`
-   Add `prepareTextDays with missing forecast uses climate normals`.
-   Add `prepareTextDays with no data still shows column`.
-   Verify that `isVisible` is correctly set even when data is missing (ensuring columns are allocated).

#### 2. Create `NavigationUtilsTest.kt`
-   Test `getDayOffsets` with `numColumns` from 1 to 10.
-   Test `getDayOffsets` with `skipHistory = true` (evening mode).
-   Verify that the size of the returned list matches expectations (e.g., `numColumns` or 1).

#### 3. Create `conductor/daily-forecast-test-plan.md`
-   Document the testing strategy for the daily view.
-   Include edge cases: 
    -   API switching (NWS to Open-Meteo).
    -   Navigation to the extreme past/future.
    -   Foldable resize events (transitioning from narrow to wide).
    -   Missing climate normals data entirely.

## Verification & Testing
-   Run all newly created tests: `./gradlew testDebugUnitTest`.
-   Ensure 100% pass rate on the new logic.
