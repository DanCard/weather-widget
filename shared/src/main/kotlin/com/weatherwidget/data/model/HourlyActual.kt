package com.weatherwidget.data.model

/**
 * One archived hour of what [source] drew as observed at a site: the blended result, not a station
 * reading. Written once by `HourlyActualsArchiveRun` and never rewritten. Null = not known; a rain
 * amount of 0 is measured dry.
 */
data class HourlyActual(
    val hourMs: Long,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
    val cloudCoverLow: Int? = null,
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    val archivedAt: Long,
)
