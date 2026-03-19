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

data class CurrentTemperatureResolution(
    val displayTemp: Float?,
    val estimatedTemp: Float?,
    val observedTemp: Float?,
    val isStaleEstimate: Boolean,
    val appliedDelta: Float?,
    val updatedDeltaState: CurrentTemperatureDeltaState?,
    val shouldClearStoredDelta: Boolean,
)

/**
 * Resolves widget header temperature from two sources:
 * - estimated current temperature from hourly interpolation,
 * - observed/API current temperature fallback.
 */
object CurrentTemperatureResolver {
    private const val TAG = "CurrentTempResolver"
    private const val STALE_HOURLY_FETCH_THRESHOLD_MS = 2 * 60 * 60 * 1000L
    private const val DELTA_DECAY_WINDOW_MS = 4 * 60 * 60 * 1000L
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

    fun resolve(
        now: LocalDateTime,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        observedCurrentTemp: Float?,
        observedCurrentTempFetchedAt: Long?,
        storedDeltaState: CurrentTemperatureDeltaState?,
        currentLat: Double,
        currentLon: Double,
    ): CurrentTemperatureResolution {
        debugLog(
            "resolve:start now=$now source=${displaySource.id} hourlyCount=${hourlyForecasts.size} " +
                "observedTemp=$observedCurrentTemp observedFetchedAt=$observedCurrentTempFetchedAt " +
                "currentLat=$currentLat currentLon=$currentLon hasStoredDelta=${storedDeltaState != null}",
        )
        val estimatedTemp =
            interpolator.getInterpolatedTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = now,
                source = displaySource,
            )
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        debugLog("resolve:estimatedTemp=$estimatedTemp nowMs=$nowMs")
        val scopeMatch =
            storedDeltaState?.let {
                it.sourceId == displaySource.id &&
                    kotlin.math.abs(it.locationLat - currentLat) < 0.000001 &&
                    kotlin.math.abs(it.locationLon - currentLon) < 0.000001
            } ?: false
        debugLog(
            "resolve:storedDelta=" +
                storedDeltaState?.let {
                    "delta=${it.delta} observed=${it.lastObservedTemp} fetchedAt=${it.lastObservedFetchedAt} " +
                        "updatedAt=${it.updatedAtMs} source=${it.sourceId} lat=${it.locationLat} lon=${it.locationLon}"
                } +
                " scopeMatch=$scopeMatch",
        )
        val scopedStoredDelta = if (scopeMatch) storedDeltaState else null
        var appliedDelta =
            scopedStoredDelta?.let {
                val decay = getDecayedDelta(
                    rawDelta = it.delta,
                    updatedAtMs = it.updatedAtMs,
                    nowMs = nowMs,
                )
                debugLog(
                    "resolve: delta raw=${it.delta}, decayed=${decay.decayedDelta}, " +
                        "elapsedMs=${decay.elapsedMs}, decayPercent=${(decay.decayFraction * 100f)}",
                )
                decay.decayedDelta
            }
        var updatedDeltaState: CurrentTemperatureDeltaState? = null

        if (observedCurrentTemp != null && observedCurrentTempFetchedAt != null && estimatedTemp != null) {
            val hasNewObservedReading = scopedStoredDelta?.lastObservedFetchedAt != observedCurrentTempFetchedAt
            debugLog(
                "resolve:observed+estimated available hasNewObservedReading=$hasNewObservedReading " +
                    "storedFetchedAt=${scopedStoredDelta?.lastObservedFetchedAt}",
            )
            if (scopedStoredDelta == null || hasNewObservedReading) {
                val delta = observedCurrentTemp - estimatedTemp
                appliedDelta = delta
                updatedDeltaState =
                    CurrentTemperatureDeltaState(
                        delta = delta,
                        lastObservedTemp = observedCurrentTemp,
                        lastObservedFetchedAt = observedCurrentTempFetchedAt,
                        updatedAtMs = observedCurrentTempFetchedAt.coerceAtMost(nowMs),
                        sourceId = displaySource.id,
                        locationLat = currentLat,
                        locationLon = currentLon,
                    )
                debugLog(
                    "resolve:updatedDeltaState rawDelta=$delta updatedAt=${updatedDeltaState.updatedAtMs} " +
                        "observedTemp=$observedCurrentTemp estimatedTemp=$estimatedTemp",
                )
            } else {
                debugLog("resolve:reusing existing stored delta without update")
            }
        } else {
            debugLog(
                "resolve:delta update skipped observedTemp=$observedCurrentTemp " +
                    "observedFetchedAt=$observedCurrentTempFetchedAt estimatedTemp=$estimatedTemp",
            )
        }

        val isStaleEstimate = estimatedTemp != null && isStaleHourlyData(now, displaySource, hourlyForecasts)
        val displayTemp =
            when {
                estimatedTemp != null -> estimatedTemp + (appliedDelta ?: 0f)
                else -> observedCurrentTemp
            }
        debugLog(
            "resolve:result displayTemp=$displayTemp estimatedTemp=$estimatedTemp observedTemp=$observedCurrentTemp " +
                "appliedDelta=$appliedDelta isStaleEstimate=$isStaleEstimate " +
                "shouldClearStoredDelta=${storedDeltaState != null && !scopeMatch}",
        )

        return CurrentTemperatureResolution(
            displayTemp = displayTemp,
            estimatedTemp = estimatedTemp,
            observedTemp = observedCurrentTemp,
            isStaleEstimate = isStaleEstimate,
            appliedDelta = appliedDelta,
            updatedDeltaState = updatedDeltaState,
            shouldClearStoredDelta = storedDeltaState != null && !scopeMatch,
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

    private data class DeltaDecay(
        val decayedDelta: Float,
        val elapsedMs: Long,
        val decayFraction: Float,
    )

    private fun getDecayedDelta(
        rawDelta: Float,
        updatedAtMs: Long,
        nowMs: Long,
    ): DeltaDecay {
        val elapsedMs = (nowMs - updatedAtMs).coerceAtLeast(0L)
        if (elapsedMs >= DELTA_DECAY_WINDOW_MS) {
            return DeltaDecay(
                decayedDelta = 0f,
                elapsedMs = elapsedMs,
                decayFraction = 0f,
            )
        }

        val remainingFraction = 1f - (elapsedMs.toFloat() / DELTA_DECAY_WINDOW_MS.toFloat())
        return DeltaDecay(
            decayedDelta = rawDelta * remainingFraction,
            elapsedMs = elapsedMs,
            decayFraction = remainingFraction,
        )
    }
}
