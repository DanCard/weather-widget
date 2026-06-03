package com.weatherwidget.data.model

data class HourlyForecast(
    val dateTime: Long,
    val temperature: Float,
    val condition: String,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
)

data class DailyForecast(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
)

data class DailyActual(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
)

/**
 * A single weather observation, in the pure model layer so [ForecastResult] doesn't depend on the
 * desktop persistence package. The persistence layer maps this to its own entity for storage.
 */
data class ObservationReading(
    val stationId: String,
    val stationName: String,
    val timestamp: Long, // epoch ms
    val temperature: Float, // Fahrenheit
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val maxTempLast24h: Float? = null, // Fahrenheit
    val minTempLast24h: Float? = null, // Fahrenheit
    val api: String,
    val precipAmountMm: Float? = null,
)

data class ForecastResult(
    val currentTemp: Float? = null,
    val currentCondition: String? = null,
    val currentObservedAt: Long? = null,
    val daily: List<DailyForecast> = emptyList(),
    val hourly: List<HourlyForecast> = emptyList(),
    val dailyActuals: Map<String, DailyActual> = emptyMap(),
    val rawObservations: List<ObservationReading> = emptyList(),
)

sealed class DataStatus {
    data object Loading : DataStatus()
    data class Live(val updatedAt: Long) : DataStatus()
    data class Stale(val updatedAt: Long, val reason: StaleReason) : DataStatus()
    data object NoData : DataStatus()
}

enum class StaleReason { OFFLINE, SOURCE_ERROR }

fun isOfflineException(e: Throwable): Boolean {
    val name = e::class.qualifiedName ?: ""
    if (name.contains("ConnectException") ||
        name.contains("UnknownHostException") ||
        name.contains("SocketTimeoutException") ||
        name.contains("NoRouteToHostException") ||
        name.contains("NetworkUnreachableException")
    ) return true
    val msg = e.message?.lowercase() ?: return false
    return msg.contains("connection refused") ||
        msg.contains("connection timed out") ||
        msg.contains("no route to host") ||
        msg.contains("network is unreachable") ||
        msg.contains("failed to connect") ||
        msg.contains("resolve")
}

fun deriveDataStatus(
    cachePresent: Boolean,
    lastFetchMs: Long?,
    refreshFailed: Boolean,
    failureIsOffline: Boolean,
    now: Long = System.currentTimeMillis(),
): DataStatus {
    if (!cachePresent && !refreshFailed) return DataStatus.Loading
    if (!cachePresent && refreshFailed) return DataStatus.NoData
    val updatedAt = lastFetchMs ?: now
    return if (refreshFailed) {
        val reason = if (failureIsOffline) StaleReason.OFFLINE else StaleReason.SOURCE_ERROR
        DataStatus.Stale(updatedAt, reason)
    } else {
        DataStatus.Live(updatedAt)
    }
}
