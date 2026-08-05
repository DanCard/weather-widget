package com.weatherwidget.data.model

import com.weatherwidget.data.remote.NwsApi

data class HourlyForecast(
    val dateTime: Long,
    val temperature: Float,
    val condition: String,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
    val source: String? = null,
    val fetchedAt: Long = 0L,
    // Storage-key coordinates, carried so the shared selection logic can collapse same-site
    // fragments (float-keyed rows that GPS jitter splits into per-precision silos). Null for
    // consumers that don't read from the location-keyed tables.
    val locationLat: Double? = null,
    val locationLon: Double? = null,
)

data class DailyForecast(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val isClimateNormal: Boolean = false,
    // Source this row was produced for, and NWS's native 12-hour daytime/nighttime period rain
    // chances — used as a fallback by the shared daily rain-label selection
    // (DailyRainLabels.resolveDailyLabelPrecip) when hourly rows are missing, keeping desktop and
    // Android identical.
    val source: String? = null,
    val daytimePrecipProbability: Int? = null,
    val nighttimePrecipProbability: Int? = null,
)

data class DailyForecastSnapshot(
    val date: String,
    val highTemp: Float?,
    val lowTemp: Float?,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val fetchedAt: Long,
    // Daytime/nighttime period chance from the original forecast — needed so the desktop daily view can
    // keep showing a past day's forecast rain chance (the snapshot is the only precip source for past
    // days, since the live `DailyForecast` list holds only today + future).
    val daytimePrecipProbability: Int? = null,
    val nighttimePrecipProbability: Int? = null,
)

data class DailyActual(
    val date: String,
    val computedHighTemp: Float,
    val computedLowTemp: Float,
    val condition: String,
    val apiHighTemp: Float? = null,
    val apiLowTemp: Float? = null,
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
    val fetchedAt: Long = System.currentTimeMillis(),
    val precipAmountMm: Float? = null,
    val isWebFallback: Boolean = false,
    // Reading failed the upstream QC check (see NwsApi.Observation.qcFailed). Stored and shown
    // in the stations UI for transparency, but excluded from blends and extrema.
    val qcFailed: Boolean = false,
)

data class ForecastResult(
    val currentTemp: Float? = null,
    val currentCondition: String? = null,
    val currentObservedAt: Long? = null,
    val appliedDelta: Float? = null,
    val daily: List<DailyForecast> = emptyList(),
    val hourly: List<HourlyForecast> = emptyList(),
    val dailyActuals: Map<String, DailyHistory> = emptyMap(),
    val dailySnapshots: Map<String, List<DailyForecastSnapshot>> = emptyMap(),
    val rawObservations: List<ObservationReading> = emptyList(),
    val nwsDailyExtremes: NwsApi.DailyTemperatureExtremes? = null,
)

sealed class DataStatus {
    data object Loading : DataStatus()
    data class Live(val updatedAt: Long) : DataStatus()
    data class Stale(val updatedAt: Long, val reason: StaleReason) : DataStatus()
    data object NoData : DataStatus()
    data class Error(val message: String) : DataStatus()
}

enum class StaleReason { OFFLINE, SOURCE_ERROR }

// Name-based half of [isOfflineException], usable where only the class name survives (e.g. the
// CURRENT_TEMP_STATUS app_logs rows the desktop UI reads back across the process boundary).
fun isOfflineExceptionName(name: String): Boolean =
    name.contains("ConnectException") ||
        name.contains("UnknownHostException") ||
        // Ktor CIO surfaces DNS failure as java.nio.channels.UnresolvedAddressException, whose
        // message is null — the message fallbacks in isOfflineException can never catch it.
        name.contains("UnresolvedAddressException") ||
        name.contains("SocketTimeoutException") ||
        name.contains("NoRouteToHostException") ||
        name.contains("NetworkUnreachableException")

fun isOfflineException(e: Throwable): Boolean {
    if (isOfflineExceptionName(e::class.qualifiedName ?: "")) return true
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
