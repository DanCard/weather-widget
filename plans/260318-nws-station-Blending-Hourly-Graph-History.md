# IDW Blending for Hourly Graph History

## Context
The current temp display now uses IDW-blended observations (from the previous change). However, the hourly graph still picks a **single station's** observation series via `selectObservationSeries()`. The user wants the hourly graph to also show IDW-blended temperatures for consistency.

## Current Data Flow (hourly graph)
1. `buildHourDataList()` receives raw `List<ObservationEntity>` (all stations, all sources)
2. `selectObservationSeries()` groups by `stationId`, picks the one with best hour coverage
3. The winning station's observations are injected as sub-hourly actuals into the graph
4. Past hours without an observation carry forward the last known actual

## Approach: Replace `selectObservationSeries` with IDW blending

### Modify `buildHourDataList()` in `TemperatureViewHandler.kt` (~line 566)

Instead of calling `selectObservationSeries()` to pick one station, **blend all NWS observations per time bucket** using `SpatialInterpolator.interpolateIDW()`.

**Steps:**
1. Filter observations by source (reuse `matchesObservationSource`)
2. Group observations by their truncated time (to nearest ~15 min or exact timestamp)
3. For each time bucket with 2+ stations, call `SpatialInterpolator.interpolateIDW()`
4. For buckets with 1 station, use it directly (no change from current behavior)
5. Build the `actualMap` from blended values instead of single-station values

**Key detail — time bucketing:** NWS stations report at slightly different minutes within the hour. We need to group observations that are "close enough" in time. Options:
- **Truncate to hour** (simple, matches current `observationHour()` logic) — but loses sub-hourly resolution
- **Round to nearest 15 min** — preserves sub-hourly granularity while allowing cross-station blending

Since the graph already injects sub-hourly actuals, I'll **keep exact timestamps** but blend observations that fall within the same hour. For each hour bucket, produce one blended point at the median timestamp.

### New function: `blendObservationSeries()` in `TemperatureViewHandler.kt`

```kotlin
internal fun blendObservationSeries(
    observations: List<ObservationEntity>,
    displaySource: WeatherSource,
    userLat: Double,
    userLon: Double,
    startHour: LocalDateTime,
    endHour: LocalDateTime,
): List<ObservationEntity>
```

**High-resolution blending algorithm:**
1. Filter by `matchesObservationSource` and time range
2. Sort all observations by timestamp
3. Walk through chronologically; for each observation, find all other stations' observations within a **±15 minute window**
4. If peers found: IDW-blend all observations in that window → produce one synthetic `ObservationEntity` at the primary observation's exact timestamp
5. If no peers: keep the observation as-is (single station)
6. **Dedup by proximity**: after blending, skip observations whose timestamps are within ~5 minutes of an already-emitted point (prevents near-duplicate entries when stations report at e.g. :53 and :56)

This preserves sub-hourly resolution. If station A reports at :23 and station B reports at :26, they'll be blended together at :23 (or whichever is processed first). The ±15 min window is generous enough to catch stations reporting in different minutes of the same observation cycle, but tight enough to avoid blending across hours.

**Concrete implementation detail:**
- Use the "primary station" approach: iterate observations sorted by time. For each, gather peers (other stations within ±15 min). Blend. Mark peers as consumed so they don't produce duplicate output points.
- The closest station's metadata (stationId, stationName, condition) is used for the synthetic entity.

Then in `buildHourDataList`, replace:
```kotlin
val selectedSeries = selectObservationSeries(actuals, displaySource, startHour, endHour)
// ... selectedSeries.observations.forEach { obs -> ...
```
with:
```kotlin
val blendedActuals = blendObservationSeries(actuals, displaySource, lat, lon, startHour, endHour)
// ... blendedActuals.forEach { obs -> ...
```

### Update `SelectedObservationSeries` usage

The `SelectedObservationSeries` data class is used for logging (stationId, stationType, rejectedGroupCount). After blending, the concept of "one selected station" doesn't apply. Update the log line to show the blend info instead (e.g., "IDW blend from N stations").

### Keep `selectObservationSeries` for now

Don't delete it — it's `@VisibleForTesting` and may have tests. Just stop calling it from `buildHourDataList`. We can clean it up later.

## Files to Modify
- **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`** — add `blendObservationSeries()`, update `buildHourDataList()` to use it
- **`app/src/main/java/com/weatherwidget/util/SpatialInterpolator.kt`** — already exists, no changes needed (it works with `List<ObservationEntity>`)

## Reuse
- `SpatialInterpolator.interpolateIDW()` — already handles staleness, time-spread, near-zero guard
- `matchesObservationSource()` — existing source filter in `TemperatureViewHandler`
- `observationHour()` — existing hour truncation helper

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — ensure no regressions
2. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
3. Check logcat: `adb logcat -s TemperatureViewHandler` — should show IDW blend info
4. Visual check: hourly graph should show smooth blended actuals instead of single-station readings
