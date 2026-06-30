# Plan: Fix Daily Forecast View Actual High Temp Bug

## 1. Context & Rationale

### Why do we recompute history, and for how many days?
You asked: *"Why recompute for past 5 days? Maybe 3 days, Maybe 2 days? History doesn't change so why recompute?"*

Your intuition is 100% correct: history itself does not change, so constantly recomputing past days is wasteful and risks overwriting good data if observations get pruned. The only reasons the app ever recomputes past daily extremes are:
1. **Late-Arriving Observations**: Weather stations sometimes report values with a delay of a few hours (or up to 24–48 hours due to transmission delays or network issues).
2. **Synoptic Fallback / Backfills**: If NWS had gaps and we fall back to Synoptic API, observations for the last 2–3 days might be backfilled on a delay.
3. **Active/Recent Transitions**: Yesterday and today are still evolving or have just completed.

Recomputing the last **3 days** (today, yesterday, and 2 days ago) is the optimal window:
* It is more than wide enough to capture any delayed observations or backfills.
* It avoids wasting database resources on older, stable dates.
* Since we are increasing raw observation retention to **10 days**, a **3-day recompute window** leaves a massive **7-day safety buffer**. This guarantees we never attempt to recompute extremes using pruned, partial observations at the edge of the retention window.

---

## 2. Proposed Changes

### A. Increase Raw Observation Retention to 10 Days
Update [ForecastRepository.cleanOldData](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt#L981-L991) to clean up observations older than 10 days instead of 6 days.

```kotlin
// In ForecastRepository.cleanOldData
val tenDaysAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 10 // 10 days
observationDao.deleteOldObservations(tenDaysAgoTimestamp)
```

### B. Adjust Recomputation Lookback to 3 Days (2 days ago to yesterday)
1. In [WeatherWidgetWorker.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt#L271-L274) (`fetchDailyActuals`), reduce lookback from 30 days to 2 days ago:
   ```kotlin
   val start = LocalDate.now().minusDays(2) // today minus 2 = past 3 days (T-2, T-1, T)
   val yesterday = LocalDate.now().minusDays(1)
   weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, start, yesterday, hourlyForecasts)
   ```
2. In [ForecastHistoryActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt#L500-L505) (`backfillDailyExtremesIfNeeded`), reduce local recompute range to 2 days ago:
   ```kotlin
   val endDate = LocalDate.now()
   val startDate = endDate.minusDays(2)
   weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, startDate, endDate, emptyList())
   ```

### C. Add Safety Guard in Recomputation Logic
Add a hard boundary inside `recomputeDailyExtremesFromStoredObservations` in [ObservationRepository.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt#L562-L574) to skip any recomputation for dates older than 9 days (1 day before the 10-day pruning cutoff) as a safety measure.

```kotlin
// In ObservationRepository.recomputeDailyExtremesFromStoredObservations
val today = LocalDate.now()
val cutoffDate = today.minusDays(9) // Safe observation limit before 10-day pruning
var current = startDate
while (!current.isAfter(endDateInclusive)) {
    if (!current.isBefore(cutoffDate)) {
        recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts)
    } else {
        Log.d(TAG, "recomputeDailyExtremesFromStoredObservations: skipping pruned date $current")
    }
    current = current.plusDays(1)
}
```

### D. Update Unit Tests
1. Update `cleanOldData uses 6-day retention for observations and 30-day for others` in [WeatherRepositoryTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryTest.kt#L239-L253) to verify the new 10-day retention cutoff.
2. Add a unit test in [ObservationRepositoryDailyMergeTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryDailyMergeTest.kt) to verify that `recomputeDailyExtremesFromStoredObservations` safely skips dates older than 9 days.

---

## 3. Verification Plan

1. **Unit Tests**: Run `./gradlew test` to ensure all tests pass, including modified/new retention and recompute window tests.
2. **Database Integrity**: Verify the database schema and behavior on the emulator.
