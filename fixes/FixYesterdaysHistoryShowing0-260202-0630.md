# Fix Yesterday's History Showing 0

## Problem
Widget displays `H=0` for yesterday because the first station (AW020, a CWOP citizen station) has no data. Official stations like KNUQ have data but aren't tried.

## Solution: Try stations in order until data found

Try stations in NWS proximity order. If one returns no observations, try the next. Log each attempt so fallbacks are visible in the API log.

## File to Modify

### `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

**Change `fetchDayObservations()` (around line 380-421):**

Current logic:
```kotlin
val stationId = stations.first()
// ... fetch observations from single station
```

New logic:
```kotlin
// Try stations in proximity order until one has data
for (stationId in stations.take(5)) {  // Limit to 5 attempts
    Log.d(TAG, "fetchDayObservations: Trying station $stationId for $date")

    val observations = nwsApi.getObservations(stationId, startTime, endTime)

    if (observations.isNotEmpty()) {
        Log.d(TAG, "fetchDayObservations: Got ${observations.size} observations from $stationId")
        // Calculate high/low and return
        val temps = observations.map { (it.temperatureCelsius * 9 / 5 + 32).toInt() }
        return temps.maxOrNull()!! to temps.minOrNull()!!
    }

    Log.d(TAG, "fetchDayObservations: No data from $stationId, trying next station")
}

Log.w(TAG, "fetchDayObservations: No observations found from any station for $date")
return null
```

Key changes:
- Loop through stations (limited to first 5 for efficiency)
- Log each attempt (visible in API diagnostics)
- Return as soon as data is found
- Log when falling back to next station

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Open Settings to trigger refresh
3. Check logcat for station fallback:
   ```bash
   adb logcat | grep -i "fetchDayObservations"
   ```
   Should show: `Trying station AW020` → `No data, trying next` → `Got X observations from KNUQ`
4. Verify widget displays non-zero high/low for yesterday
5. Check API log in Settings shows the station attempts
