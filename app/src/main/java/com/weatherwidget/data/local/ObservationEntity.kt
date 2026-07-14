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
    primaryKeys = ["stationId", "timestamp"],
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
)
