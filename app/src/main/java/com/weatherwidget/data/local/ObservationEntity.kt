package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Stores individual observations from specific weather stations.
 * Used to capture "micro-climate" discrepancies between nearby stations (e.g., NWS airports vs. PWS).
 * Serves as source of truth for actual weather data in accuracy calculations.
 */
@Entity(
    tableName = "observations",
    primaryKeys = ["stationId", "timestamp", "locationLat", "locationLon"],
    indices = [
        Index(value = ["locationLat", "locationLon"]),
        Index(value = ["timestamp", "locationLat", "locationLon"]),
        Index(value = ["api"]),
    ],
)
data class ObservationEntity(
    val stationId: String,
    val stationName: String,
    val timestamp: Long, // Epoch ms
    val temperature: Float, // Fahrenheit
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val fetchedAt: Long = System.currentTimeMillis(),
    val maxTempLast24h: Float? = null, // Fahrenheit; from NWS maxTemperatureLast24Hours (rolling 24h ASOS extreme)
    val minTempLast24h: Float? = null, // Fahrenheit; from NWS minTemperatureLast24Hours (rolling 24h ASOS extreme)
    val api: String, // Which API provided this observation (NWS, OPEN_METEO, WEATHER_API, SILURIAN)
    val precipAmountMm: Float? = null, // Observed precipitation amount in mm
    val isWebFallback: Boolean = false, // <-- Added
    val qcFailed: Boolean = false, // Rejected by upstream QC; shown in stations UI, excluded from blends
    val cloudCover: Int? = null, // Total-column cloud cover 0-100; null for rows that carry none
    val cloudCoverLow: Int? = null, // Low-layer only 0-100; what the cloud graph draws
)

/**
 * Returns a copy with the device location snapped to the shared write-key grid
 * ([LocationMatch.quantize]). Apply at every `observations` insert boundary so one physical site is
 * always keyed identically regardless of which source resolved the coordinate — raw-double writes
 * (e.g. `37.416797…`) and 3-dp writes (`37.417`) otherwise become separate keys that the read-path
 * `selectNearestSite` can split (plan 260721, Fix A). Idempotent: quantizing an already-quantized
 * coordinate is a no-op.
 */
fun ObservationEntity.withQuantizedLocation(): ObservationEntity =
    copy(
        locationLat = LocationMatch.quantize(locationLat),
        locationLon = LocationMatch.quantize(locationLon),
    )

/**
 * Collapses a coarse location-box query to the nearest stored observation site.
 *
 * Observation identity includes the fetch site, so rows for two nearby widget locations can now
 * coexist instead of replacing each other. Every coordinate-scoped read must therefore select one
 * physical site before blending or aggregating the rows.
 */
fun selectNearestObservationSite(
    observations: List<ObservationEntity>,
    latitude: Double,
    longitude: Double,
): List<ObservationEntity> =
    LocationMatch.selectNearestSite(
        observations,
        latitude,
        longitude,
        ObservationEntity::locationLat,
        ObservationEntity::locationLon,
    )

fun ObservationEntity.toReading() = com.weatherwidget.data.model.ObservationReading(
    stationId = stationId,
    stationName = stationName,
    timestamp = timestamp,
    temperature = temperature,
    condition = condition,
    locationLat = locationLat,
    locationLon = locationLon,
    distanceKm = distanceKm,
    stationType = stationType,
    maxTempLast24h = maxTempLast24h,
    minTempLast24h = minTempLast24h,
    api = api,
    fetchedAt = fetchedAt,
    precipAmountMm = precipAmountMm,
    isWebFallback = isWebFallback,
    qcFailed = qcFailed,
    cloudCover = cloudCover,
    cloudCoverLow = cloudCoverLow,
)
