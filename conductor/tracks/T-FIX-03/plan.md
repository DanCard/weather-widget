# Track T-FIX-03: Triple Bar Today Fix & NWS Backfill Enhancement

## Problem Description
The "today" slot rendering in the daily forecast graph showed an incorrect "actuals" bar, often appearing as a dot at the low temperature. This was due to sparse historical observations for the current day and a bug in the NWS backfill logic. Additionally, the "three bars" for today were redundant as they all used the same forecast data.

## Implementation Plan
- [x] **Enhance NWS Backfill (`CurrentTempRepository.kt`)**:
    -   Update `backfillNwsObservationsIfNeeded` to check today's observation density.
    -   Trigger backfill if past 2 AM and sparse points (< 3-8 depending on hour).
- [x] **Update Estimator (`DailyActualsEstimator.kt`)**:
    -   Add `snapshotHigh` and `snapshotLow` to `TodayTripleLineValues`.
    -   Update `calculateTodayTripleLineValues` to incorporate `currentTemp` into the observed range.
- [x] **Refactor Rendering (`DailyForecastGraphRenderer.kt`)**:
    -   Add `snapshotHigh` and `snapshotLow` to `DayData`.
    -   Update `renderGraph` to draw 3 distinct bars:
        1. **Snapshot Forecast** (Yellow): The prediction from ~24h ago.
        2. **Observed** (Orange): Actual captured observations including current temperature.
        3. **Current Forecast** (Blue): The latest prediction.
- [x] **Data Preparation (`DailyViewLogic.kt`)**:
    -   In `prepareGraphDays`, find the oldest forecast snapshot from >12-24h ago.
    -   Resolve current observed temperature from `currentTemps`.
    -   Pass all values to `calculateTodayTripleLineValues` and populate `DayData`.
- [x] **Cleanup**:
    -   Resolve compiler warnings for redundant conversions and non-null assertions in `DailyForecastGraphRenderer.kt`.

## Verification & Testing
- [x] **Unit Tests**:
    - Added `prepareGraphDays includes snapshot and current temp for today` to `DailyViewHandlerTest.kt`.
    - Verified all 25 tests in `DailyViewHandlerTest.kt` pass.
- [x] **Manual Verification**:
    - Confirmed NWS backfill triggers correctly on sparse data.
    - Verified today's slot shows 3 distinct bars with proper height and colors.
