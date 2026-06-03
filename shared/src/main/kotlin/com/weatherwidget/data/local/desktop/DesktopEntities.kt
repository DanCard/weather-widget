package com.weatherwidget.data.local.desktop

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
)

data class DesktopDailyExtremeEntity(
    val date: Long,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val updatedAt: Long,
    val precipAmountMm: Float? = null,
    val precipDayMm: Float? = null,
    val precipNightMm: Float? = null,
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
    val forecastDate: Long,
    val source: String,
    val highTemp: Float?,
    val lowTemp: Float?,
    val fetchedAt: Long,
)
