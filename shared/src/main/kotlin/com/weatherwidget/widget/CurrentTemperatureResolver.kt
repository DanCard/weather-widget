package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.graph.SeriesSmoothing
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.TempUtils
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
    private const val HOUR_MS = 60 * 60 * 1000L
    /** Rows written by one fetch share a fetchedAt to within this. */
    internal const val RUN_TOLERANCE_MS = 10 * 60 * 1000L

    // Decoupled logging callback for writing to AppLogDao on Android or logging on Desktop
    @Volatile
    var dbLogger: ((tag: String, message: String, level: String) -> Unit)? = null

    const val HEADER_SMOOTH_ITERATIONS = 0

    data class CurrentTempResolutionWindow(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    /**
     * Hours of lookback and lookahead defining "current". Public and authoritative because more than
     * one surface has to resolve the SAME current observation: the widget header derives `observedAt`
     * here, and the daily today-column overlay re-derives it to name the dominant station. When the
     * two used different windows they disagreed on which reading was latest — the overlay requires
     * exact equality, so the station rows silently vanished while the delta row kept rendering.
     * See plans/260819-today-overlay-station-drop-and-dead-opportunistic-loop.md.
     */
    const val RESOLUTION_LOOKBACK_HOURS = 12L
    const val RESOLUTION_LOOKAHEAD_HOURS = 3L

    fun buildCurrentTempResolutionWindow(now: LocalDateTime): CurrentTempResolutionWindow {
        val truncatedNow = now.truncatedTo(ChronoUnit.HOURS)
        val roundedNow = if (now.minute >= 30) truncatedNow.plusHours(1) else truncatedNow
        return CurrentTempResolutionWindow(
            start = roundedNow.minusHours(RESOLUTION_LOOKBACK_HOURS),
            end = roundedNow.plusHours(RESOLUTION_LOOKAHEAD_HOURS),
        )
    }

    fun computeSmoothedForecasts(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        smoothIterations: Int = HEADER_SMOOTH_ITERATIONS,
        now: Long = System.currentTimeMillis(),
    ): Map<Long, Float> {
        // pickBestForecast returns null for buckets with rows from neither the display source nor
        // GENERIC_GAP (per-source strictness); drop those buckets rather than assert them present.
        val bucketCount = hourlyForecasts.groupBy { it.dateTime }.size
        val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapNotNull { (time, rows) -> pickBestForecast(rows, displaySourceId)?.let { time to it } }
            .toMap()
        if (forecastsByTime.size < bucketCount) {
            verboseLog(
                "computeSmoothedForecasts: dropped ${bucketCount - forecastsByTime.size}/$bucketCount " +
                    "hour buckets with no rows for source=$displaySourceId",
            )
        }
        val sortedTimes = forecastsByTime.keys.sorted()
        val rawTemps = sortedTimes.map { forecastsByTime.getValue(it).temperature }
        val smoothedTemps = SeriesSmoothing.smoothValuesPreservingAllExtrema(
            rawTemps,
            iterations = smoothIterations,
        )
        return sortedTimes.mapIndexed { index, time ->
            time to smoothedTemps[index]
        }.toMap()
    }

    private fun pickBestForecast(
        rows: List<HourlyForecast>,
        sourceId: String,
    ): HourlyForecast? {
        val candidates = when {
            rows.any { it.source == sourceId } -> rows.filter { it.source == sourceId }
            rows.any { it.source == WeatherSource.GENERIC_GAP.id } -> rows.filter { it.source == WeatherSource.GENERIC_GAP.id }
            else -> emptyList()
        }
        // The latest forecast wins for every hour, past or future. (Previously past hours used the
        // earliest snapshot — the stale 6–7-day-out long-range prediction — which inflated the
        // interpolated current temp. See HourlyForecastStitcher for the same fix on the graph line.)
        return candidates.maxByOrNull { it.fetchedAt }
    }

    // Per-resolution trace breadcrumb. VERBOSE: shown in the ephemeral sink (logcat / desktop console)
    // but dropped at the dbLogger persistence boundary, so these high-frequency lines (the resolver
    // runs every ~2 min) never swamp the queryable DB log. Use appLog for sparse, queryable summaries.
    private fun verboseLog(message: String) {
        Log.v(TAG, message)
        dbLogger?.invoke(TAG, message, "VERBOSE")
    }

    private fun appLog(
        tag: String,
        message: String,
        level: String = "DEBUG",
    ) {
        when (level) {
            "VERBOSE" -> Log.v(tag, message)
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
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
        // Level for the CURR_TEMP_RESULT summary row. Sparse callers (Android widget updates,
        // desktop fetch-cycle loads) keep the persisted DEBUG default; high-frequency callers
        // (desktop per-panel-poll / per-minute-ticker resolution) pass VERBOSE, which the
        // dbLogger boundary drops, so they can't swamp app_logs with a write per poll.
        resultLogLevel: String = "DEBUG",
    ): CurrentTemperatureResolution {
        val window = buildCurrentTempResolutionWindow(now)
        val zoneId = ZoneId.systemDefault()
        val minMs = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxMs = window.end.atZone(zoneId).toInstant().toEpochMilli()

        // Consistency: Even if the caller passed a 7-day list (fallback), we MUST resolve and smooth
        // against a fixed-size window to ensure the delta doesn't jump between view modes.
        val strictHourlyForecasts = hourlyForecasts.filter { it.dateTime in minMs..maxMs }

        appLog(
            "CURR_TEMP_RESOLVE",
            "resolve:start now=$now source=${displaySource.id} hourlyCount=${hourlyForecasts.size} " +
                "strictCount=${strictHourlyForecasts.size} window=${window.start.toLocalTime()}..${window.end.toLocalTime()} " +
                "obsTemp=$lastObservedTemp obsAt=$observedAt " +
                "hasStored=${storedDeltaState != null}",
            // Per-resolution start breadcrumb: VERBOSE so it doesn't persist. The CURR_TEMP_RESULT
            // outcome below stays DEBUG (persisted) — that's the one summary worth querying.
            level = "VERBOSE",
        )
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var estimatedTemp =
            resolveStrictForecastTemperature(
                hourlyForecasts = strictHourlyForecasts,
                targetTime = now,
                source = displaySource,
                smoothedForecasts = smoothedForecasts,
            )
        // Set below when both estimates are taken from the newest run instead; see [newestRunIfObservationBracketIsOlder].
        var newestRun: List<HourlyForecast>? = null
        verboseLog("resolve:estimatedTemp=$estimatedTemp nowMs=$nowMs")
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
            verboseLog(
                "resolve:storedDelta scopeMismatch=$mismatchReason requestedSource=${displaySource.id} " +
                    "requestedLat=$currentLat requestedLon=$currentLon",
            )
        }
        verboseLog(
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
            verboseLog(
                "resolve:observed available hasNewObservedReading=$hasNewObservedReading " +
                    "storedObservedAt=${scopedStoredDelta?.lastObservedAt}",
            )
            val obsTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(observedAt),
                ZoneId.systemDefault()
            )
            var estimatedAtObsTime =
                resolveStrictForecastTemperature(
                    hourlyForecasts = strictHourlyForecasts,
                    targetTime = obsTime,
                    source = displaySource,
                    smoothedForecasts = smoothedForecasts,
                )
            // Both estimates must come from ONE forecast run when the observation sits on an older
            // run that the newest run contradicts; otherwise the correction measures the old run's
            // error and is added to an estimate that already includes the newer run.
            val candidateRun = newestRunIfObservationBracketIsOlder(strictHourlyForecasts, displaySource.id, observedAt)
            if (candidateRun != null &&
                prefersNewestRun(lastObservedTemp, estimatedAtObsTime, estimateFromRun(candidateRun, observedAt))
            ) {
                newestRun = candidateRun
                estimatedAtObsTime = estimateFromRun(candidateRun, observedAt)
                estimatedTemp = estimateFromRun(candidateRun, nowMs)
            }
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
                verboseLog(
                    "resolve:anchorDelta rawDelta=$rawDelta updatedAt=${updatedDeltaState?.updatedAtMs ?: scopedStoredDelta?.updatedAtMs} " +
                        "observedTemp=$lastObservedTemp estimatedAtObs=$estimatedAtObsTime nowForecast=$estimatedTemp",
                )
            } else {
                verboseLog("resolve:anchorDelta FAILED - no forecast for observation time=$obsTime")
                appliedDelta = null
            }
        }

        val isStaleEstimate = isStaleHourlyData(now, displaySource, strictHourlyForecasts)
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
                "estAtObs=${formatTemp(estimatedAtObservationTime)} stale=$isStaleEstimate" +
                (newestRun?.let { " run=newest_only runFetchedAt=${it.maxOf { row -> row.fetchedAt }}" } ?: ""),
            level = resultLogLevel,
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
        useCelsius: Boolean,
    ): String {
        val displayVal = TempUtils.display(temp, useCelsius)
        return when {
            numColumns >= 2 -> String.format("%.1f°", displayVal)
            else -> String.format("%.0f°", displayVal)
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
        verboseLog(
            "isStaleHourlyData: source=${displaySource.id} scopedCount=${sourceScopedForecasts.size} " +
                "latestFetchMs=$latestFetchMs ageMs=${nowMs - latestFetchMs} thresholdMs=$STALE_HOURLY_FETCH_THRESHOLD_MS stale=$stale",
        )
        return stale
    }

    /**
     * The display source's newest forecast run, when the rows bracketing the observation time come
     * from an older one; null when they are already the newest (the usual case) or absent.
     *
     * Rows are picked newest-per-hour, but a provider that only forecasts future slots never
     * refreshes an hour once it has passed. OWM's free `/2.5/forecast` (2026-10-03): 13:00-16:00
     * from a 10:35 run that ran ~10° cool, 17:00+ from a 16:42 run. The correction
     * `observed − estimate(15:47)` (+9.70) then measured the old run's error and was added to an
     * estimate interpolating into the fresh 17:00 row — 100.4°F on desktop, 110°F on the emulator,
     * against stations reading ~90-95. A run is the rows within [RUN_TOLERANCE_MS] of the newest
     * `fetchedAt` (one fetch writes them together).
     * Plan: plans/261003-current-temp-delta-same-forecast-run.md.
     */
    internal fun newestRunIfObservationBracketIsOlder(
        hourlyForecasts: List<HourlyForecast>,
        sourceId: String,
        observedAt: Long?,
    ): List<HourlyForecast>? {
        if (observedAt == null) return null
        val own = hourlyForecasts.filter { it.source == sourceId }
        if (own.isEmpty()) return null
        val runStartMs = own.maxOf { it.fetchedAt } - RUN_TOLERANCE_MS
        val zoneId = ZoneId.systemDefault()
        val obsHourMs = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(observedAt), zoneId)
            .truncatedTo(ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val bracket = own.filter { it.dateTime == obsHourMs || it.dateTime == obsHourMs + HOUR_MS }
            .groupBy { it.dateTime }
            .values.map { rows -> rows.maxBy { it.fetchedAt } }
        if (bracket.isEmpty() || bracket.all { it.fetchedAt >= runStartMs }) return null
        val run = own.filter { it.fetchedAt >= runStartMs }
            .groupBy { it.dateTime }
            .values.map { rows -> rows.maxBy { it.fetchedAt } }
            .sortedBy { it.dateTime }
        return run.ifEmpty { null }
    }

    /**
     * Use the newest run only when it explains the observation better than the per-hour estimate
     * built on the older run. Fetch boundaries are routine (NWS refreshes each hour while it is
     * current, so every past hour comes from a different fetch) and consecutive runs usually agree;
     * replacing them would flatten a real trend. On 2026-10-03 the older OWM run missed the
     * observation by 9.7 (desktop) and 18 (emulator), the newest by 4.3 and 0.7.
     */
    internal fun prefersNewestRun(observed: Float, olderRunEstimate: Float?, newestRunEstimate: Float): Boolean =
        olderRunEstimate == null || kotlin.math.abs(observed - newestRunEstimate) < kotlin.math.abs(observed - olderRunEstimate)

    /** Estimate from one run: interpolate between its slots; hold its first/last slot flat outside them. */
    internal fun estimateFromRun(run: List<HourlyForecast>, targetMs: Long): Float {
        if (targetMs <= run.first().dateTime) return run.first().temperature
        if (targetMs >= run.last().dateTime) return run.last().temperature
        val i = run.indexOfLast { it.dateTime <= targetMs }
        return TemperatureInterpolator.getInterpolatedTemperature(listOf(run[i], run[i + 1]), targetMs)
            ?: run[i].temperature
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
                .mapValues { (_, rows) -> pickBestForecast(rows, source.id) }

        val currentHourForecast = sourceScopedForecasts[targetHourMs]
        val nextHourForecast = sourceScopedForecasts[nextHourMs]

        if (currentHourForecast == null) {
            verboseLog(
                "resolve:strictForecast unavailable target=$targetTime reason=missing_current_hour " +
                    "targetHourMs=$targetHourMs nextHourMs=$nextHourMs",
            )
            return null
        }

        if (targetTime.minute == 0 && targetTime.second == 0 && targetTime.nano == 0) {
            return smoothedForecasts?.get(currentHourForecast.dateTime) ?: currentHourForecast.temperature
        }

        if (nextHourForecast == null) {
            verboseLog(
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
