# Session Log: Investigating Today's Low Temperature Reversion (2026-04-14)

## Overview
The user reported that the Samsung device shows an incorrect low temperature for "Today" on the daily graph. Occasionally, the correct low (approx 46°F) appears briefly after a widget boot but then reverts to a higher value (approx 60°F, the current temperature) after a few seconds. The emulator does not exhibit this behavior and correctly maintains the low.

## 1. Initial Code Research
I began by tracing the data flow for the daily forecast view:
- **`DailyViewLogic.prepareGraphDays`**: The central function for preparing graph data. For "Today", it calls `DailyActualsEstimator.calculateTodayTripleLineValues`.
- **`DailyActualsEstimator.calculateTodayTripleLineValues`**:
    - Takes `dailyActuals` (a map of `LocalDate` to `DailyActual`) as input.
    - Defines `observedLow` as `minOf(actual.lowTemp, currentTemp)`.
    - Logged evidence from the Samsung device showed `actual.low=46.30016` in one update, but then `actual.low=60.53` in a subsequent update.
- **`ObservationRepository.getDailyActualsWithLiveToday`**: This is the source of the `dailyActuals` map. It has two distinct paths:
    1. **Past Days**: Reads directly from the `daily_extremes` table (cached/persistent).
    2. **Today**: Explicitly ignores the cache and recomputes the low/high live from the `observations` table.

## 2. Empirical Investigation (Samsung Device)
I used `adb` to pull the `weather_database` from the Samsung device and performed direct SQLite queries.

### Findings from `daily_extremes` Table:
- **Query**: `SELECT * FROM daily_extremes WHERE lowTemp < 50;`
- **Result**: Found a row for `2026-04-14` (Today) with `lowTemp = 46.3001594543457`.
- **Conclusion**: The "true" low was correctly captured and persisted in the dedicated extremes table earlier in the day (likely from a rolling 24h extreme reported by an NWS station).

### Findings from `observations` Table:
- **Query**: `SELECT * FROM observations WHERE temperature < 50 OR minTempLast24h < 50;`
- **Result**: **Empty.**
- **Conclusion**: The raw observation record that originally supplied the 46.3°F value is no longer in the `observations` table. This could be due to:
    - **Cleanup**: Observations are cleaned up after 4 days.
    - **Location Jitter**: The `getObservationsInRange` query uses a `BETWEEN lat - 0.1 AND lat + 0.1` filter. If the device moved slightly or the station's reported location shifted, the historical record might be filtered out even if it still exists.

## 3. Root Cause: The "Reversion" Mechanism
1. **The Initial Paint (Correct)**:
   When the widget first loads (or during a full system update), `WeatherWidgetProvider` or `WidgetIntentRouter` might load the full history from `daily_extremes`. Because the `daily_extremes` table contains the 46.3°F record for today, it displays correctly.

2. **The Re-computation (Incorrect)**:
   A few seconds later, a background task (like `WeatherWidgetWorker`) or a navigation intent triggers a refresh. These paths call `getDailyActualsWithLiveToday`.
   - This function sees the date is "Today".
   - It queries the `observations` table for the last 24 hours.
   - Because the 46.3°F record is missing from `observations` (but present in `daily_extremes`), the re-computation only sees the current temperature (approx 60°F).
   - It returns 60°F as the "observed low" for today.
   - The UI updates, and the low "reverts" from 46°F to 60°F.

## 4. Proposed Fix
The `daily_extremes` table is designed with a "persistence guard" in `recomputeDailyExtremesForDay`:
```kotlin
val updatedLow = minOf(existing.lowTemp, new.lowTemp)
// ... only update if high is higher OR low is lower.
```
However, this guard is bypassed for the "Today" display because `getDailyActualsWithLiveToday` doesn't look at the table for the current date.

**Action**: Modify `getDailyActualsWithLiveToday` to:
1. Fetch the persistent low/high from `daily_extremes` for today.
2. Fetch the live-computed low/high from `observations` for today.
3. Merge them using `minOf` (for low) and `maxOf` (for high).

This ensures that once a low is recorded (especially from NWS rolling extremes), it remains the "low of the day" regardless of whether the raw observation record is cleaned up or filtered out.

## 5. Metadata
- **Date**: 2026-04-14
- **Device**: Samsung SM-F936U1 (Samsung Galaxy Z Fold 4)
- **Status**: Root cause identified; Implementation plan drafted.
