# Daily Forecast View History: How Actuals Are Sourced

**Date:** 2026-05-16
**Question:** Does the location's "actual" high/low for a historical day come from a single station, or is it blended from multiple stations?

## Answer

**Blended from multiple stations via Inverse Distance Weighting (IDW).** Not a single station's reading.

## Flow

### 1. Multi-station fetch
`ObservationRepository.backfillNwsObservations()` (lines 148-228) walks through up to 5 nearby NWS stations (`MAX_RETRIES = 5`). Each station's raw observations are stored as their own `ObservationEntity` rows, tagged with the originating `stationId` and `distanceKm`.

Fallback is *additive*, not strictly sequential: it keeps pulling from additional stations to maximize coverage rather than stopping at the first station that returns anything.

### 2. Per-station daily extreme
`ObservationResolver.computeDailyExtremes()` (lines 174-216) groups raw observations by station, then derives a (high, low) per station:

```kotlin
val byStation = obs.groupBy { it.stationId }.values.map { stObs ->
    val maxExtreme = stObs.mapNotNull { it.maxTempLast24h }.maxOrNull()
    val minExtreme = stObs.mapNotNull { it.minTempLast24h }.minOrNull()
    val maxSpot = stObs.maxOf { it.temperature }
    val minSpot = stObs.minOf { it.temperature }

    StationData(
        distanceKm = stObs.first().distanceKm,
        high = maxOf(maxExtreme ?: maxSpot, maxSpot),
        low  = minOf(minExtreme ?: minSpot, minSpot),
    )
}
```

Key detail: it prefers NWS's official `maxTempLast24h` / `minTempLast24h` when present, falling back to the max/min of hourly spot readings. The belt-and-suspenders `maxOf(... , maxSpot)` guarantees the result is never less than what was actually observed.

### 3. Spatial blend (IDW)
Per-station `(distanceKm, high)` and `(distanceKm, low)` pairs are passed to `SpatialInterpolator.interpolateIDWValues()`. Closer stations get more weight; weight scales as 1/distance^p.

```kotlin
val high = SpatialInterpolator.interpolateIDWValues(highPairs) ?: obs.maxOf { it.temperature }
val low  = SpatialInterpolator.interpolateIDWValues(lowPairs)  ?: obs.minOf { it.temperature }
```

### 4. Storage
- **Historical days:** `DailyExtremeEntity` stores a `source` field (no per-station ID — the row is already a blend).
- **Today's live view:** `ObservationBlender.blendObservationSeries()` writes the synthetic marker `stationId = "NWS_BLEND"` (line 523 of ObservationRepository) so future debuggers can identify blended rows directly in the DB.

## Key files
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:174-216` — `computeDailyExtremes` (the blend)
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt:148-228` — multi-station fetch loop
- `app/src/main/java/com/weatherwidget/util/ObservationBlender.kt:139-231` — live "today" blending path
- `app/src/main/java/com/weatherwidget/util/SpatialInterpolator.kt` — IDW implementation

## Notes / design rationale

- **IDW vs. nearest-neighbor:** Nearest-station alone is fragile — a single miscalibrated sensor or microclimate (airport runway running hot) silently corrupts the actual. IDW preserves "closer counts more" while distributing the risk.
- **24h-extreme preference is load-bearing:** Hourly spot polling misses the true daily max/min that fell between samples. NWS publishes `maxTempLast24h` / `minTempLast24h` precisely for this. The code prefers them and never goes lower than the spot readings actually saw.
- **`"NWS_BLEND"` provenance marker:** Rather than nulling out `stationId` when blending, they make the synthesis explicit. Matches the CLAUDE.md note that `stationId` exists "for transparency and debugging."
