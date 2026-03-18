# Plan: Triple Bar Today Fix & NWS Backfill Enhancement

## Objective
Fix the "today" slot rendering in the daily forecast graph where the actuals bar (orange) often shows only the low temperature (appears as a dot) and doesn't represent the full "observed so far" range. This is primarily caused by missing historical observations for the current day due to a bug in the NWS backfill logic. Additionally, improve the "3 bars" representation for today to show:
1.  **Snapshot Forecast** (Yellow): The prediction from ~24h ago.
2.  **Observed So Far** (Orange): Actual captured observations including the current temperature.
3.  **Current Forecast** (Blue): The latest prediction.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`: NWS backfill logic.
- `app/src/main/java/com/weatherwidget/util/DailyActualsEstimator.kt`: Calculation of "today" triple-line values.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Data preparation for the daily graph.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Drawing logic for the daily bars.

## Proposed Changes

### 1. Enhance NWS Backfill (`CurrentTempRepository.kt`)
- Modify `backfillNwsObservationsIfNeeded` to also check today's observation count.
- If it's past 2 AM and we have fewer than (e.g.) 3 observations for today, trigger a backfill.
- This ensures that a fresh app start or new location fetches today's earlier points from NWS.

### 2. Update `DailyActualsEstimator.kt`
- Add `snapshotHigh` and `snapshotLow` to `TodayTripleLineValues`.
- Update `calculateTodayTripleLineValues` to:
    - Accept an optional `currentTemp: Float?` and incorporate it into the `observedHigh`/`observedLow` range.
    - Accept optional `snapshotHigh: Float?` and `snapshotLow: Float?` to pass through.

### 3. Update `DailyViewLogic.kt`
- In `prepareGraphDays`, for the "today" slot:
    - Identify an appropriate forecast snapshot (e.g., the oldest one fetched at least 12-24 hours ago).
    - Retrieve the current temperature for the display source.
    - Pass these values to `DailyActualsEstimator.calculateTodayTripleLineValues`.
- Populate `DailyForecastGraphRenderer.DayData` with the new snapshot fields.

### 4. Update `DailyForecastGraphRenderer.kt`
- Add `snapshotHigh` and `snapshotLow` to `DayData`.
- In `renderGraph`, update the `isToday` drawing block:
    - Use `snapshotHigh` and `snapshotLow` for the Yellow bar (`todayTripleYellowPaint`).
    - Use `high` and `low` (Observed) for the Orange bar (`todayTripleOrangePaint`).
    - Use `forecastHigh` and `forecastLow` (Current Forecast) for the Blue bar (`todayTripleBluePaint`).
- Ensure fallback logic handles missing snapshot data gracefully (e.g., falling back to current forecast if no snapshot exists).

## Implementation Steps

1.  **Modify `CurrentTempRepository.kt`**:
    - Update `backfillNwsObservationsIfNeeded` check.
2.  **Modify `DailyActualsEstimator.kt`**:
    - Update `TodayTripleLineValues` data class.
    - Update `calculateTodayTripleLineValues` signature and implementation.
3.  **Modify `DailyForecastGraphRenderer.kt`**:
    - Update `DayData` data class.
    - Update `renderGraph` "Today" drawing logic to use 3 distinct sources.
4.  **Modify `DailyViewLogic.kt`**:
    - Update `prepareGraphDays` to find snapshots and current temp, and pass them to the estimator.
5.  **Verify with `DailyViewHandlerTest.kt`**:
    - Update existing tests to account for the new "triple line" definition.
    - Add a test case for missing observations to ensure it doesn't just show a dot at the low if `currentTemp` is available.

## Verification & Testing
- **Unit Tests**:
    - Run `DailyViewHandlerTest.kt` to verify data preparation logic.
    - Verify `ObservationResolverTest.kt` still correctly aggregates data.
- **Manual Verification (Emulator)**:
    - Clear app data.
    - Set location (triggers backfill).
    - Verify today's slot shows 3 bars with distinct heights if forecast has moved or observations exist.
    - Check adb logs for `NWS_BACKFILL` triggers.
