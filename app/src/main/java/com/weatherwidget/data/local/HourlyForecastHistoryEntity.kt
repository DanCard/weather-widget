package com.weatherwidget.data.local

/**
 * A row of the one hourly table ([HourlyForecastEntity]) read or written with its label, for callers
 * that work on every fetch's rows rather than the newest per hour.
 */
data class HourlyForecastHistoryEntity(
    val dateTime: Long, // Epoch ms of the forecasted hour
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float, // Fahrenheit
    val condition: String,
    val source: String,
    val timestampToGroupPredictions: Long, // Bucket-aligned epoch ms identifying when this prediction was captured
    val precipProbability: Int? = null, // 0-100
    val cloudCover: Int? = null, // 0-100
    val precipAmountMm: Float? = null,
    val fetchedAt: Long, // Real fetch time
    val cloudCoverLow: Int? = null, // Low-layer only (0-100)
    val cloudCoverMid: Int? = null, // Mid-layer only (0-100)
    val cloudCoverHigh: Int? = null, // High-layer only (0-100)
)

/** The one hourly table's primary key. */
data class HourlyRowKey(
    val dateTime: Long,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val timestampToGroupPredictions: Long,
)

fun HourlyForecastHistoryEntity.key() = HourlyRowKey(dateTime, source, locationLat, locationLon, timestampToGroupPredictions)

/** Carries fetchedAt and the storage-key coordinates so the shared site selectors can collapse fragments. */
fun HourlyForecastHistoryEntity.toHourlyForecast() = com.weatherwidget.data.model.HourlyForecast(
    dateTime = dateTime,
    temperature = temperature,
    condition = condition,
    precipProbability = precipProbability,
    precipAmountMm = precipAmountMm,
    cloudCover = cloudCover,
    cloudCoverLow = cloudCoverLow,
    cloudCoverMid = cloudCoverMid,
    cloudCoverHigh = cloudCoverHigh,
    source = source,
    fetchedAt = fetchedAt,
    locationLat = locationLat,
    locationLon = locationLon,
)
