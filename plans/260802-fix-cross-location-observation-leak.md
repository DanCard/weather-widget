# Plan: Fix Cross-Location Observation Leak in CurrentTempRepository

**Date:** 2026-08-02
**Target File:** `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`

## Symptom & Root Cause Analysis

### Symptom
Hourly temperature graphs on two emulators set to the same active location (Mountain View, CA) displayed conflicting historical actual curves (`-2.5 from yesterday` vs `+3.9 from yesterday`, and peak temperatures of `86°F` vs `83.3°F`).

### Root Cause
In `CurrentTempRepository.kt`:

1. **`getPointsOfInterest(latitude, longitude)`** includes distant `historical_pois` (e.g., Austin, TX `30.27, -97.74` or Mendocino `39.237, -123.15`) alongside local cardinal sample points (North, South, East, West).
2. **Incorrect Location Keying**: When fetching weather for these points in `fetchForecastCurrent` and `fetchOpenMeteoCurrent`, `ObservationEntity` is created using the **active widget's location** (`latitude`, `longitude`) as `locationLat`/`locationLon`, instead of the point's actual coordinates (`point.first`, `point.second`):
   ```kotlin
   val obsEntity = ObservationEntity(
       stationId = stationId,
       stationName = "Meteo: ${point.third}",
       timestamp = reading.observedAt ?: System.currentTimeMillis(),
       temperature = reading.temperature, // Distant temperature (e.g. Austin 97.1°F)
       condition = condition,
       locationLat = latitude,   // <--- ACTIVE WIDGET LAT (Mountain View)
       locationLon = longitude,  // <--- ACTIVE WIDGET LON (Mountain View)
       ...
   )
   ```
3. **Database Contamination**: The `observations` table stores these distant readings (Austin, Mendocino) under Mountain View's coordinates. When rendering the hourly graph or calculating actuals for Mountain View, the spatial interpolator retrieves these contaminated rows, distorting the local actuals curve.

---

## Proposed Changes

### 1. `CurrentTempRepository.kt`

#### A. Remove Distant Historical POIs from Local Spatial Sampling
Modify `getPointsOfInterest(latitude, longitude)` so it only returns spatial sample points local to the target location (Current + North/South/East/West offsets of ~0.07-0.09° / ~5 miles). Remove `getHistoricalPois()` iteration from local spatial sampling. Historical POIs should not be fetched or inserted as local observations for a different active location.

```kotlin
private fun getPointsOfInterest(latitude: Double, longitude: Double): List<Triple<Double, Double, String>> {
    return listOf(
        Triple(latitude, longitude, "Current"),
        Triple(latitude + 0.072, longitude, "North"),
        Triple(latitude - 0.072, longitude, "South"),
        Triple(latitude, longitude + 0.09, "East"),
        Triple(latitude, longitude - 0.09, "West"),
    )
}
```

#### B. Use Actual Sample Point Coordinates for `ObservationEntity`
In `fetchForecastCurrent` and `fetchOpenMeteoCurrent`, ensure the `ObservationEntity` uses `point.first` and `point.second` for `locationLat` and `locationLon` (or quantized coordinates of `point.first`/`point.second`), so each observation record accurately reflects where it was sampled:

```kotlin
val obsEntity = ObservationEntity(
    stationId = stationId,
    stationName = "$stationLabelPrefix: ${point.third}",
    timestamp = result.currentObservedAt ?: System.currentTimeMillis(),
    temperature = currentTemp,
    condition = condition,
    locationLat = point.first,   // Actual sample latitude
    locationLon = point.second,  // Actual sample longitude
    distanceKm = calculateDistance(latitude, longitude, point.first, point.second) / 1000f,
    stationType = "OFFICIAL",
    api = source.id,
)
```

#### C. Database Cleanup / Purge Contaminated Rows
Add a cleanup query or maintenance step on startup / repository init to purge contaminated observation rows from the SQLite `observations` table:
- Delete rows where `stationId` starts with `OPEN_METEO_`, `WEATHER_API_`, `VISUAL_CROSSING_`, etc. and `stationName` contains `Recent:`.

---

## Verification Plan

### Automated Tests
1. **Unit Tests**:
   - Verify `getPointsOfInterest` only generates local spatial points.
   - Test that observations inserted from sample points carry the sample point's coordinates.
   - Run existing unit test suite: `./gradlew test`.

### Manual / Emulator Verification
1. Clean up contaminated DB rows on emulators (`emulator-5554` and `emulator-5556`).
2. Refresh weather on both emulators set to Mountain View, CA.
3. Query `observations` database table on both emulators to confirm no distant `Recent:` station observations exist.
4. Capture screenshots of both emulators and verify hourly graphs match exactly.
