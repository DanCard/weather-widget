package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.ObservationReading

data class DesktopObservationEntity(
    val stationId: String,
    val stationName: String,
    val timestamp: Long,
    val temperature: Float,
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val fetchedAt: Long = System.currentTimeMillis(),
    val maxTempLast24h: Float? = null,
    val minTempLast24h: Float? = null,
    val api: String,
    val precipAmountMm: Float? = null,
    val isWebFallback: Boolean = false,
    val qcFailed: Boolean = false,
    val cloudCover: Int? = null,
    val cloudCoverLow: Int? = null,
    // Actual METAR vs the interleaved ASOS 5-minute row; the cloud blend prefers METARs.
    val isMetar: Boolean = false,
) {
    companion object {
        /**
         * Synthetic station id for the internal multi-station IDW blend. It is an aggregate, not a
         * real station, so it must never be shown in the observations UI (mirrors the Android
         * widget's `ActualPrecipSource.NWS_BLEND_STATION_ID`).
         */
        const val NWS_BLEND_STATION_ID = "NWS_BLEND"
    }
}

fun DesktopObservationEntity.toReading() = ObservationReading(
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
    isMetar = isMetar,
)

fun ObservationReading.toEntity(fetchedAt: Long) = DesktopObservationEntity(
    stationId = stationId,
    stationName = stationName,
    timestamp = timestamp,
    temperature = temperature,
    condition = condition,
    locationLat = locationLat,
    locationLon = locationLon,
    distanceKm = distanceKm,
    stationType = stationType,
    fetchedAt = fetchedAt,
    maxTempLast24h = maxTempLast24h,
    minTempLast24h = minTempLast24h,
    api = api,
    precipAmountMm = precipAmountMm,
    isWebFallback = isWebFallback,
    qcFailed = qcFailed,
    cloudCover = cloudCover,
    cloudCoverLow = cloudCoverLow,
    isMetar = isMetar,
)

/** A persistent app-log row (desktop analogue of the Android app_logs table). */
data class DesktopLogEntity(
    val timestamp: Long, // epoch ms
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * Slim projection of a `forecasts` row — just the columns the accuracy calculator needs to match a
 * forecast to its actual and compute the error.
 */
data class DesktopForecastRow(
    val targetDate: Long,
    val dateOfPrediction: Long,
    val source: String,
    val highTemp: Float?,
    val lowTemp: Float?,
    val fetchedAt: Long,
)

data class DesktopStationCacheEntity(
    val cacheKey: String,
    val stations: String,
    val updatedAt: Long,
)

data class CurrentTempStatus(
    val timestamp: Long,
    val ok: Boolean,
    val message: String
)
