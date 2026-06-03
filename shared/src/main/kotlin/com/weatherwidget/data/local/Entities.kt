package com.weatherwidget.data.local

data class ObservationEntity(
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

data class DailyExtremeEntity(
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
