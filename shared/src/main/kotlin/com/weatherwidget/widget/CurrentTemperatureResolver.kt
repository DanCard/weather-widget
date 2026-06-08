package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CurrentTemperatureResolution(
    val displayTemp: Float?,
    val estimatedTemp: Float?,
    val observedTemp: Float?,
    val isStaleEstimate: Boolean,
    val appliedDelta: Float?,
    val updatedDeltaState: CurrentTemperatureDeltaState?,
    val shouldClearStoredDelta: Boolean,
)

data class QuickCurrentTemperature(
    val displayTemp: Float?,
    val estimatedTemp: Float?,
    val observedTemp: Float?,
    val isStaleEstimate: Boolean,
)

/**
 * Resolves widget/desktop temperature from two sources:
 * - estimated current temperature from hourly interpolation,
 * - observed/API current temperature fallback.
 */
object CurrentTemperatureResolver {
    private const val TAG = "CurrentTempResolver"
    private const val STALE_HOURLY_FETCH_THRESHOLD_MS = 2 * 60 * 60 * 1000L

    // Decoupled logging callback for writing to AppLogDao on Android or logging on Desktop
    @Volatile
    var dbLogger: ((tag: String, message: String, level: String) -> Unit)? = null

    const val HEADER_SMOOTH_ITERATIONS = 0

    data class CurrentTempResolutionWindow(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    fun buildCurrentTempResolutionWindow(now: LocalDateTime): CurrentTempResolutionWindow {
        val truncatedNow = now.truncatedTo(ChronoUnit.HOURS)
        val roundedNow = if (now.minute >= 30) truncatedNow.plusHours(1) else truncatedNow
        return CurrentTempResolutionWindow(
            start = roundedNow.minusHours(12L),
            end = roundedNow.plusHours(3L),
        )
    }

    fun computeSmoothedForecasts(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        smoothIterations: Int = HEADER_SMOOTH_ITERATIONS,
    ): Map<Long, Float> {
        val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapValues { entry ->
                entry.value.find { it.source == displaySourceId }
                    ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: entry.value.firstOrNull()
            }
        val sortedTimes = forecastsByTime.keys.sorted()
        val rawTemps = sortedTimes.map { forecastsByTime[it]!!.temperature }
        val smoothedTemps = TemperatureInterpolator.smoothValuesPreservingAllExtrema(
            rawTemps,
            iterations = smoothIterations,
        )
        return sortedTimes.mapIndexed { index, time ->
            time to smoothedTemps[index]
        }.toMap()
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        dbLogger?.invoke(TAG, message, "DEBUG")
    }

    private fun appLog(
        tag: String,
        message: String,
        level: String = "DEBUG",
    ) {
        Log.d(tag, message)
        dbLogger?.invoke(tag, message, level)
    }

    private fun formatTemp(value: Float?): String =
        value?.let { String.format("%.2f", it) } ?: "none"

    fun resolve(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecast>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        storedDeltaState: CurrentTemperatureDeltaState?,
        currentLat: Double,
        currentLon: Double,
        smoothedForecasts: Map<Long, Float>? = null,
    ): CurrentTemperatureResolution {
        appLog(
            "CURR_TEMP_RESOLVE",
            "resolve:start now=$now source=${displaySource.id} hourlyCount=${hourlyForecasts.size} " +
                "obsTemp=$lastObservedTemp obsAt=$observedAt " +
                "lat=$currentLat lon=$currentLon hasStored=${storedDeltaState != null}",
        )
        val estimatedTemp =
            resolveStrictForecastTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = now,
                source = displaySource,
                smoothedForecasts = smoothedForecasts,
            )
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        debugLog("resolve:estimatedTemp=$estimatedTemp nowMs=$nowMs")
        val scopeMatch =
            storedDeltaState?.let {
                it.sourceId == displaySource.id &&
                    kotlin.math.abs(it.locationLat - currentLat) < 0.000001 &&
                    kotlin.math.abs(it.locationLon - currentLon) < 0.000001
            } ?: false
        if (storedDeltaState != null && !scopeMatch) {
            val mismatchReason =
                buildList {
                    if (storedDeltaState.sourceId != displaySource.id) add("source")
                    if (kotlin.math.abs(storedDeltaState.locationLat - currentLat) >= 0.000001) add("lat")
                    if (kotlin.math.abs(storedDeltaState.locationLon - currentLon) >= 0.000001) add("lon")
                }.joinToString(",")
            debugLog(
                "resolve:storedDelta scopeMismatch=$mismatchReason requestedSource=${displaySource.id} " +
                    "requestedLat=$currentLat requestedLon=$currentLon",
            )
        }
        debugLog(
            "resolve:storedDelta=" +
                storedDeltaState?.let {
                    "delta=${it.delta} observed=${it.lastObservedTemp} observedAt=${it.lastObservedAt} " +
                        "updatedAt=${it.updatedAtMs} source=${it.sourceId} lat=${it.locationLat} lon=${it.locationLon}"
                } +
                " scopeMatch=$scopeMatch",
        )
        val scopedStoredDelta = if (scopeMatch) storedDeltaState else null
        var appliedDelta: Float? = scopedStoredDelta?.delta
        var updatedDeltaState: CurrentTemperatureDeltaState? = null

        var estimatedAtObservationTime: Float? = null

        if (lastObservedTemp != null && observedAt != null) {
            val hasNewObservedReading = scopedStoredDelta?.lastObservedAt != observedAt
            debugLog(
                "resolve:observed available hasNewObservedReading=$hasNewObservedReading " +
                    "storedObservedAt=${scopedStoredDelta?.lastObservedAt}",
            )
            val obsTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(observedAt),
                ZoneId.systemDefault()
            )
            val estimatedAtObsTime =
                resolveStrictForecastTemperature(
                    hourlyForecasts = hourlyForecasts,
                    targetTime = obsTime,
                    source = displaySource,
                    smoothedForecasts = smoothedForecasts,
                )
            estimatedAtObservationTime = estimatedAtObsTime

            if (estimatedAtObsTime != null) {
                val rawDelta = lastObservedTemp - estimatedAtObsTime
                appliedDelta = rawDelta
                if (scopedStoredDelta == null || hasNewObservedReading || scopedStoredDelta.delta != rawDelta) {
                    updatedDeltaState =
                        CurrentTemperatureDeltaState(
                            delta = rawDelta,
                            lastObservedTemp = lastObservedTemp,
                            lastObservedAt = observedAt,
                            updatedAtMs = observedAt.coerceAtMost(nowMs),
                            sourceId = displaySource.id,
                            locationLat = currentLat,
                            locationLon = currentLon,
                        )
                }
                debugLog(
                    "resolve:anchorDelta rawDelta=$rawDelta updatedAt=${updatedDeltaState?.updatedAtMs ?: scopedStoredDelta?.updatedAtMs} " +
                        "observedTemp=$lastObservedTemp estimatedAtObs=$estimatedAtObsTime nowForecast=$estimatedTemp",
                )
            } else {
                debugLog("resolve:anchorDelta FAILED - no forecast for observation time=$obsTime")
                appliedDelta = null
            }
        }

        val isStaleEstimate = isStaleHourlyData(now, displaySource, hourlyForecasts)
        val displayTemp =
            if (estimatedTemp != null && appliedDelta != null) {
                estimatedTemp + appliedDelta
            } else {
                estimatedTemp ?: lastObservedTemp
            }

        appLog(
            "CURR_TEMP_RESULT",
            "resolve:final display=${formatTemp(displayTemp)} estimate=${formatTemp(estimatedTemp)} " +
                "obs=${formatTemp(lastObservedTemp)} delta=${appliedDelta?.let { String.format("%.2f", it) } ?: "none"} " +
                "estAtObs=${formatTemp(estimatedAtObservationTime)} stale=$isStaleEstimate",
        )

        return CurrentTemperatureResolution(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = lastObservedTemp,
            isStaleEstimate = isStaleEstimate,
            appliedDelta = appliedDelta,
            updatedDeltaState = updatedDeltaState,
            shouldClearStoredDelta = !scopeMatch && storedDeltaState != null,
        )
    }

    fun resolveQuick(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecast>,
        lastObservedTemp: Float?,
        smoothedForecasts: Map<Long, Float>? = null,
    ): QuickCurrentTemperature {
        val estimatedTemp =
            resolveStrictForecastTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = now,
                source = displaySource,
                smoothedForecasts = smoothedForecasts,
            )
        val isStaleEstimate = isStaleHourlyData(now, displaySource, hourlyForecasts)
        val displayTemp = estimatedTemp ?: lastObservedTemp

        return QuickCurrentTemperature(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = lastObservedTemp,
            isStaleEstimate = isStaleEstimate,
        )
    }

    private fun applySmoothing(
        forecasts: List<HourlyForecast>,
        smoothed: Map<Long, Float>?
    ): List<HourlyForecast> {
        return forecasts.map { entity ->
            val temp = smoothed?.get(entity.dateTime) ?: entity.temperature
            entity.copy(temperature = temp)
        }
    }

    fun formatDisplayTemperature(
        temp: Float,
        numColumns: Int,
        isStaleEstimate: Boolean,
    ): String {
        return when {
            numColumns >= 2 -> String.format("%.1f°", temp)
            else -> String.format("%.0f°", temp)
        }
    }

    private fun isStaleHourlyData(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecast>,
    ): Boolean {
        if (hourlyForecasts.isEmpty()) return false

        val sourceScopedForecasts =
            hourlyForecasts.filter {
                it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
            }
        if (sourceScopedForecasts.isEmpty()) return false

        val latestFetchMs = sourceScopedForecasts.map { it.fetchedAt }.maxOrNull() ?: return false
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stale = (nowMs - latestFetchMs) > STALE_HOURLY_FETCH_THRESHOLD_MS
        debugLog(
            "isStaleHourlyData: source=${displaySource.id} scopedCount=${sourceScopedForecasts.size} " +
                "latestFetchMs=$latestFetchMs ageMs=${nowMs - latestFetchMs} thresholdMs=$STALE_HOURLY_FETCH_THRESHOLD_MS stale=$stale",
        )
        return stale
    }

    private fun resolveStrictForecastTemperature(
        hourlyForecasts: List<HourlyForecast>,
        targetTime: LocalDateTime,
        source: WeatherSource,
        smoothedForecasts: Map<Long, Float>?,
    ): Float? {
        if (hourlyForecasts.isEmpty()) return null

        val zoneId = ZoneId.systemDefault()
        val targetHour = targetTime.truncatedTo(ChronoUnit.HOURS)
        val nextHour = targetHour.plusHours(1)
        val targetHourMs = targetHour.atZone(zoneId).toInstant().toEpochMilli()
        val nextHourMs = nextHour.atZone(zoneId).toInstant().toEpochMilli()

        val sourceScopedForecasts =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == source.id }
                        ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                        ?: entry.value.firstOrNull()
                }

        val currentHourForecast = sourceScopedForecasts[targetHourMs]
        val nextHourForecast = sourceScopedForecasts[nextHourMs]

        if (currentHourForecast == null) {
            debugLog(
                "resolve:strictForecast unavailable target=$targetTime reason=missing_current_hour " +
                    "targetHourMs=$targetHourMs nextHourMs=$nextHourMs",
            )
            return null
        }

        if (targetTime.minute == 0 && targetTime.second == 0 && targetTime.nano == 0) {
            return smoothedForecasts?.get(currentHourForecast.dateTime) ?: currentHourForecast.temperature
        }

        if (nextHourForecast == null) {
            debugLog(
                "resolve:strictForecast unavailable target=$targetTime reason=missing_next_hour " +
                    "targetHourMs=$targetHourMs nextHourMs=$nextHourMs",
            )
            return null
        }

        return TemperatureInterpolator.getInterpolatedTemperature(
            hourlyForecasts = applySmoothing(listOf(currentHourForecast, nextHourForecast), smoothedForecasts),
            targetEpochMs = targetTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
    }
}
