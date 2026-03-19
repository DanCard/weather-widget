# Restore Observation Temperature Extremes Fallback

## Objective
Restore the logic that calculates daily temperature extremes (high/low) from spot observations when official rolling 24h extremes (e.g., from NWS ASOS) are unavailable. This is necessary for smaller weather stations that do not provide these pre-computed extremes.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`: Contains the logic for aggregating observations into daily extremes.
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`: Manages fetching and saving observations and extremes.

## Proposed Solution
1. **Update `ObservationResolver.kt`**:
   - Rename `officialExtremesToDailyEntities` to `computeDailyExtremes` to better reflect its broader scope.
   - Modify the implementation to include a fallback: for each (date, source) group, if no official extremes (`maxTempLast24h`, `minTempLast24h`) are found, compute the high and low from the available spot observations for that group.

2. **Update `ObservationRepository.kt`**:
   - Restore the `recomputeDailyExtremesForDay` private method. This method queries all observations for a specific day and calls `ObservationResolver.computeDailyExtremes` to update the `daily_extremes` table.
   - Update `fetchStationObservation` to call `recomputeDailyExtremesForDay` after inserting a new observation.
   - Update `backfillNwsObservationsIfNeeded` to call `recomputeDailyExtremesForDay` for each day that received new observations during the backfill process.

## Implementation Steps
### 1. ObservationResolver.kt
- Rename `officialExtremesToDailyEntities` to `computeDailyExtremes`.
- Update the implementation to group by date and source, then find high/low using:
  - Max of `maxTempLast24h` and `minTempLast24h` if ANY are present in the group.
  - Fallback to `maxOf { it.temperature }` and `minOf { it.temperature }` if NONE are present in the group.
- Example:
```kotlin
                val officialHighs = dayObs.mapNotNull { it.maxTempLast24h }
                val officialLows = dayObs.mapNotNull { it.minTempLast24h }
                val highTemp = if (officialHighs.isNotEmpty()) officialHighs.max() else dayObs.maxOf { it.temperature }
                val lowTemp = if (officialLows.isNotEmpty()) officialLows.min() else dayObs.minOf { it.temperature }
```

### 2. ObservationRepository.kt
- Restore `recomputeDailyExtremesForDay(latitude: Double, longitude: Double, referenceTimestamp: Long)`:
```kotlin
    private suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        referenceTimestamp: Long,
    ) {
        val zone = ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(referenceTimestamp).atZone(zone).toLocalDate()
        val startTs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayObs = observationDao.getObservationsInRange(startTs, endTs, latitude, longitude)
        if (dayObs.isNotEmpty()) {
            val extremes = ObservationResolver.computeDailyExtremes(dayObs, latitude, longitude)
            dailyExtremeDao.insertAll(extremes)
        }
    }
```
- Update `fetchStationObservation` to call `recomputeDailyExtremesForDay(latitude, longitude, obsEntity.timestamp)` instead of `officialExtremesToDailyEntities`.
- Update `backfillNwsObservationsIfNeeded` to calculate distinct day timestamps from the backfilled observations and call `recomputeDailyExtremesForDay` for each:
```kotlin
                    val distinctDayTimestamps = entities.map { e ->
                        val zone = ZoneId.systemDefault()
                        java.time.Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }.distinct()
                    for (dayTs in distinctDayTimestamps) {
                        recomputeDailyExtremesForDay(latitude, longitude, dayTs + 3_600_000L) // +1h to be safe in the middle of the day
                    }
```

## Verification & Testing
- **Unit Test**: Verify `ObservationResolver.computeDailyExtremes` with:
  - Observations having official extremes.
  - Observations missing official extremes (verify fallback to spot min/max).
  - Mixed observations.
- **Manual Verification**: Verify on an emulator that a station without official extremes (e.g., many non-ASOS NWS stations or other sources) now populates the daily forecast "Actual" values.
