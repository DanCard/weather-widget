package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.TemperatureInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * Resolves widget header temperature from two sources:
 * - estimated current temperature from hourly interpolation,
 * - observed/API current temperature fallback.
 */
object CurrentTemperatureResolver {
    private const val TAG = "CurrentTempResolver"
    private const val STALE_HOURLY_FETCH_THRESHOLD_MS = 2 * 60 * 60 * 1000L
    private val interpolator = TemperatureInterpolator()
    @Volatile
    private var defaultAppLogDao: AppLogDao? = null
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setDefaultAppLogDao(appLogDao: AppLogDao?) {
        defaultAppLogDao = appLogDao
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        val dao = defaultAppLogDao ?: return
        logScope.launch {
            dao.log(TAG, message)
        }
    }

    private fun appLog(
        tag: String,
        message: String,
        level: String = "DEBUG",
    ) {
        val dao = defaultAppLogDao ?: return
        logScope.launch {
            dao.log(tag, message, level)
        }
    }

    private fun formatTemp(value: Float?): String =
        value?.let { String.format("%.2f", it) } ?: "none"

    fun resolve(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        storedDeltaState: CurrentTemperatureDeltaState?,
        currentLat: Double,
        currentLon: Double,
        smoothedForecasts: Map<Long, Float>? = null,
    ): CurrentTemperatureResolution {
        debugLog(
            "resolve:start now=$now source=${displaySource.id} hourlyCount=${hourlyForecasts.size} " +
                "observedTemp=$lastObservedTemp observedAt=$observedAt " +
                "currentLat=$currentLat currentLon=$currentLon hasStoredDelta=${storedDeltaState != null}",
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
                appliedDelta = null
                debugLog(
                    "resolve:anchorDelta unavailable observedTemp=$lastObservedTemp " +
                        "estimatedAtObs=$estimatedAtObsTime nowForecast=$estimatedTemp",
                )
            }
        } else {
            debugLog(
                "resolve:delta update skipped observedTemp=$lastObservedTemp " +
                    "observedAt=$observedAt estimatedTemp=$estimatedTemp",
            )
        }

        val isStaleEstimate = estimatedTemp != null && isStaleHourlyData(now, displaySource, hourlyForecasts)
        val displayTemp =
            when {
                lastObservedTemp != null && observedAt != null && estimatedTemp != null && estimatedAtObservationTime != null && appliedDelta != null ->
                    estimatedTemp + appliedDelta
                lastObservedTemp != null && observedAt != null -> lastObservedTemp
                estimatedTemp != null -> estimatedTemp + (appliedDelta ?: 0f)
                else -> lastObservedTemp
            }
        debugLog(
            "resolve:result displayTemp=$displayTemp estimatedTemp=$estimatedTemp observedTemp=$lastObservedTemp " +
                "appliedDelta=$appliedDelta isStaleEstimate=$isStaleEstimate " +
                "shouldClearStoredDelta=${storedDeltaState != null && !scopeMatch}",
        )
        debugLog(
            "resolve:explain nowForecast=${formatTemp(estimatedTemp)} " +
                "lastObserved=${formatTemp(lastObservedTemp)} " +
                "estimatedAtObs=${formatTemp(estimatedAtObservationTime)} " +
                "rawStoredDelta=${formatTemp(scopedStoredDelta?.delta)} " +
                "appliedDelta=${formatTemp(appliedDelta)} " +
                "displayTemp=${formatTemp(displayTemp)} " +
                "observedAt=${observedAt ?: "none"}",
        )
        logDisplaySelection(
            source = displaySource,
            nowMs = nowMs,
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = lastObservedTemp,
            observedAt = observedAt,
            appliedDelta = appliedDelta,
            estimatedAtObservationTime = estimatedAtObservationTime,
            isStaleEstimate = isStaleEstimate,
        )

        return CurrentTemperatureResolution(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = lastObservedTemp,
            isStaleEstimate = isStaleEstimate,
            appliedDelta = appliedDelta,
            updatedDeltaState = updatedDeltaState,
            shouldClearStoredDelta = storedDeltaState != null && !scopeMatch,
        )
    }

    private fun logDisplaySelection(
        source: WeatherSource,
        nowMs: Long,
        displayTemp: Float?,
        estimatedTemp: Float?,
        observedTemp: Float?,
        observedAt: Long?,
        appliedDelta: Float?,
        estimatedAtObservationTime: Float?,
        isStaleEstimate: Boolean,
    ) {
        val anchorType =
            when {
                observedTemp != null && observedAt != null && estimatedTemp != null &&
                    estimatedAtObservationTime != null && appliedDelta != null -> "observed_delta"
                observedTemp != null && observedAt != null -> "observed"
                estimatedTemp != null && appliedDelta != null -> "forecast_delta"
                estimatedTemp != null -> "forecast"
                observedTemp != null -> "observed_no_timestamp"
                else -> "none"
            }
        val displayedAgeMin = observedAt?.let { (nowMs - it) / 60_000L }
        appLog(
            "CURRENT_TEMP_DISPLAY",
            "source=${source.id} anchorType=$anchorType displayTemp=${displayTemp ?: "none"} " +
                "estimatedTemp=${estimatedTemp ?: "none"} observedTemp=${observedTemp ?: "none"} " +
                "observedAt=${observedAt ?: "none"} displayedAgeMin=${displayedAgeMin ?: "none"} " +
                "appliedDelta=${appliedDelta ?: "none"} isStaleEstimate=$isStaleEstimate",
            "INFO",
        )
    }

    fun resolveQuick(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        smoothedForecasts: Map<Long, Float>? = null,
    ): QuickCurrentTemperature {
        val estimatedTemp =
            interpolator.getInterpolatedTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = now,
                source = displaySource,
                smoothedForecasts = smoothedForecasts,
            )
        val displayTemp = lastObservedTemp ?: estimatedTemp
        val isStaleEstimate = lastObservedTemp == null && estimatedTemp != null && isStaleHourlyData(now, displaySource, hourlyForecasts)
        return QuickCurrentTemperature(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = lastObservedTemp,
            isStaleEstimate = isStaleEstimate,
        )
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
        hourlyForecasts: List<HourlyForecastEntity>,
    ): Boolean {
        if (hourlyForecasts.isEmpty()) return true

        val sourceScopedForecasts =
            hourlyForecasts.filter {
                it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id
            }
        if (sourceScopedForecasts.isEmpty()) return true

        val latestFetchMs = sourceScopedForecasts.maxOfOrNull { it.fetchedAt } ?: return true
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stale = (nowMs - latestFetchMs) > STALE_HOURLY_FETCH_THRESHOLD_MS
        debugLog(
            "isStaleHourlyData: source=${displaySource.id} scopedCount=${sourceScopedForecasts.size} " +
                "latestFetchMs=$latestFetchMs ageMs=${nowMs - latestFetchMs} thresholdMs=$STALE_HOURLY_FETCH_THRESHOLD_MS stale=$stale",
        )
        return stale
    }

    private fun resolveStrictForecastTemperature(
        hourlyForecasts: List<HourlyForecastEntity>,
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

        return interpolator.getInterpolatedTemperature(
            hourlyForecasts = listOf(currentHourForecast, nextHourForecast),
            targetTime = targetTime,
            source = null,
            smoothedForecasts = smoothedForecasts,
        )
    }

}
