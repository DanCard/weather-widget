package com.weatherwidget.util

import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ObservationResolver
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Utility for blending multi-station weather observations using Inverse Distance Weighting (IDW)
 * and forward extrapolation using forecast trends.
 */
object ObservationBlender {

    data class StationSeriesStats(
        val stationId: String,
        val rawObservationCount: Int,
        val observedPointCount: Int,
        val interpolatedPointCount: Int,
        val extrapolatedPointCount: Int,
        val outputPointCount: Int,
        val firstTimestamp: Long?,
        val lastTimestamp: Long?,
    )

    data class StationSeriesBuildResult(
        val series: Map<String, List<StationTimeSeriesPoint>>,
        val stats: List<StationSeriesStats>,
    )

    data class BlendObservationStats(
        val rawObservationCount: Int,
        val filteredObservationCount: Int,
        val stationCount: Int,
        val candidateTimeCount: Int,
        val emittedPointCount: Int,
        val dedupSkippedCount: Int,
        val emptyPeerCount: Int,
        val stationSeriesStats: List<StationSeriesStats>,
    ) {
        fun summary(topStations: Int = 3): String {
            val topStationSummary =
                stationSeriesStats
                    .sortedByDescending { it.outputPointCount }
                    .take(topStations)
                    .joinToString(";") { stats ->
                        val span =
                            if (stats.firstTimestamp != null && stats.lastTimestamp != null) {
                                "${formatClock(stats.firstTimestamp)}-${formatClock(stats.lastTimestamp)}"
                            } else {
                                "none"
                            }
                        "${stats.stationId}:raw=${stats.rawObservationCount},obs=${stats.observedPointCount}," +
                            "interp=${stats.interpolatedPointCount},extra=${stats.extrapolatedPointCount}," +
                            "out=${stats.outputPointCount},span=$span"
                    }
            return "rawObs=$rawObservationCount filteredObs=$filteredObservationCount stations=$stationCount " +
                "candidateTimes=$candidateTimeCount emitted=$emittedPointCount dedupSkipped=$dedupSkippedCount " +
                "emptyPeers=$emptyPeerCount topStations=[$topStationSummary]"
        }

        private fun formatClock(timestamp: Long): String =
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    data class BlendObservationResult(
        val observations: List<ObservationEntity>,
        val stats: BlendObservationStats,
    )

    data class StationTimeSeriesPoint(
        val timestamp: Long,
        val temperature: Float,
        val stationId: String,
        val stationName: String,
        val distanceKm: Float,
        val stationType: String,
        val sourceKind: String, // "observed", "interpolated", "forecast_extrapolated"
        val anchorTimestamp: Long,
    )

    /**
     * Resolves the current observed temperature at a specific time by running the IDW blending
     * and forward extrapolation logic.
     */
    fun resolveCurrentObservation(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        userLat: Double,
        userLon: Double,
        now: LocalDateTime = LocalDateTime.now(),
        lookbackHours: Long = 12L,
        lookaheadHours: Long = 2L,
    ): Triple<Float, Long, Long>? {
        val zoneId = ZoneId.systemDefault()
        val truncated = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (now.minute >= 30) truncated.plusHours(1) else truncated
        
        val startHour = alignedCenter.minusHours(lookbackHours)
        val endHour = alignedCenter.plusHours(lookaheadHours)
        val startMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
        val endMs = endHour.atZone(zoneId).toInstant().toEpochMilli()

        val result = blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            userLat = userLat,
            userLon = userLon,
            startMs = startMs,
            endMs = endMs,
            onBlendDebug = null
        )

        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()
        val latestObs = result.observations
            .filter { it.timestamp <= nowMs }
            .maxByOrNull { it.timestamp }

        return latestObs?.let { Triple(it.temperature, it.timestamp, it.fetchedAt) }
    }

    fun blendObservationSeries(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        userLat: Double,
        userLon: Double,
        startMs: Long,
        endMs: Long,
        onBlendDebug: ((() -> String) -> Unit)? = null,
    ): BlendObservationResult {
        val filtered = observations
            .filter { matchesObservationSource(it, displaySource) }
            .filter { it.timestamp in startMs..endMs }
            .sortedBy { it.timestamp }

        if (filtered.isEmpty()) {
            return BlendObservationResult(
                observations = emptyList(),
                stats = BlendObservationStats(
                    rawObservationCount = observations.size,
                    filteredObservationCount = 0,
                    stationCount = 0,
                    candidateTimeCount = 0,
                    emittedPointCount = 0,
                    dedupSkippedCount = 0,
                    emptyPeerCount = 0,
                    stationSeriesStats = emptyList(),
                ),
            )
        }

        val windowMs = 15 * 60 * 1000L
        val maxStationInterpolationGapMs = 3 * 60 * 60 * 1000L
        val maxStationExtrapolationGapMs = 3 * 60 * 60 * 1000L
        val dedupMs = 5 * 60 * 1000L

        val stationSeriesResult = buildStationTimeSeries(
            observations = filtered,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            interpolationStepMs = windowMs,
            maxInterpolationGapMs = maxStationInterpolationGapMs,
            maxExtrapolationGapMs = maxStationExtrapolationGapMs,
            endMs = endMs,
            onBlendDebug = onBlendDebug,
        )

        val stationSeries = stationSeriesResult.series
        val candidateTimes = stationSeries
            .values
            .flatten()
            .map { it.timestamp }
            .distinct()
            .sorted()

        val result = mutableListOf<ObservationEntity>()
        var lastEmittedMs = 0L
        var previousCohortStations: Set<String>? = null
        var dedupSkippedCount = 0
        var emptyPeerCount = 0

        val idwPairs = mutableListOf<Pair<Float, Float>>()

        for (targetTs in candidateTimes) {
            if (targetTs - lastEmittedMs < dedupMs) {
                dedupSkippedCount += 1
                continue
            }
            
            var freshestAnchorTs = -1L
            var anchor: StationTimeSeriesPoint? = null
            var bestSourceKind: String = "forecast_extrapolated"
            var hasObserved = false
            var hasInterpolated = false
            
            idwPairs.clear()
            
            stationSeries.values.forEach { points ->
                val p = resolveStationPointForTimestamp(points, targetTs, windowMs)
                if (p != null) {
                    idwPairs.add(p.distanceKm to p.temperature)
                    if (p.anchorTimestamp > freshestAnchorTs) freshestAnchorTs = p.anchorTimestamp
                    
                    val currentAbsDiff = Math.abs(p.timestamp - targetTs)
                    val anchorAbsDiff = if (anchor != null) Math.abs(anchor!!.timestamp - targetTs) else Long.MAX_VALUE
                    if (anchor == null || currentAbsDiff < anchorAbsDiff) {
                        anchor = p
                    }
                    
                    if (p.sourceKind == "observed") hasObserved = true
                    else if (p.sourceKind == "interpolated") hasInterpolated = true
                }
            }
            
            val anchorPoint = anchor ?: continue
            
            bestSourceKind = when {
                hasObserved -> "observed"
                hasInterpolated -> "interpolated"
                else -> "forecast_extrapolated"
            }
            
            val blendedTemp = if (idwPairs.size == 1) {
                idwPairs[0].second
            } else {
                SpatialInterpolator.interpolateIDWValues(idwPairs)
                    ?: anchorPoint.temperature
            }

            if (onBlendDebug != null) {
                onBlendDebug.invoke {
                    val peers = mutableListOf<StationTimeSeriesPoint>()
                    stationSeries.values.forEach { points ->
                        resolveStationPointForTimestamp(points, targetTs, windowMs)?.let { peers.add(it) }
                    }
                    val cohortStations = peers.map { it.stationId }.toSortedSet()
                    val cohortChanged = previousCohortStations != cohortStations
                    previousCohortStations = cohortStations

                    val timeStr = Instant.ofEpochMilli(targetTs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    
                    if (idwPairs.size == 1) {
                        "emit t=$timeStr single_station=${anchorPoint.stationId} temp=${String.format("%.1f", anchorPoint.temperature)} distanceKm=${String.format("%.1f", anchorPoint.distanceKm)} blended=${String.format("%.1f", blendedTemp)} source=${anchorPoint.sourceKind}"
                    } else {
                        val weightSum = idwPairs.sumOf { 1.0 / (it.first * it.first) }
                        val peerStr = StringBuilder()
                        var first = true
                        peers.forEach { p ->
                            if (!first) peerStr.append(",")
                            val w = (1.0 / (p.distanceKm * p.distanceKm)) / weightSum
                            peerStr.append("${p.stationId}:${String.format("%.1f", p.temperature)}F@${String.format("%.1f", p.distanceKm)}km(w=${String.format("%.2f", w)},${p.sourceKind})")
                            first = false
                        }
                        "emit t=$timeStr blended=${String.format("%.1f", blendedTemp)} stations=[$peerStr] cohortChanged=$cohortChanged bestSourceKind=$bestSourceKind"
                    }
                }
            }

            result.add(
                ObservationEntity(
                    stationId = anchorPoint.stationId,
                    stationName = anchorPoint.stationName,
                    timestamp = targetTs,
                    temperature = blendedTemp,
                    condition = bestSourceKind,
                    locationLat = userLat,
                    locationLon = userLon,
                    distanceKm = anchorPoint.distanceKm,
                    stationType = anchorPoint.stationType,
                    api = displaySource.id,
                    fetchedAt = freshestAnchorTs,
                ),
            )
            lastEmittedMs = targetTs
        }

        return BlendObservationResult(
            observations = result,
            stats = BlendObservationStats(
                rawObservationCount = observations.size,
                filteredObservationCount = filtered.size,
                stationCount = stationSeries.size,
                candidateTimeCount = candidateTimes.size,
                emittedPointCount = result.size,
                dedupSkippedCount = dedupSkippedCount,
                emptyPeerCount = emptyPeerCount,
                stationSeriesStats = stationSeriesResult.stats,
            ),
        )
    }

    private fun buildStationTimeSeries(
        observations: List<ObservationEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        interpolationStepMs: Long,
        maxInterpolationGapMs: Long,
        maxExtrapolationGapMs: Long,
        endMs: Long,
        onBlendDebug: ((() -> String) -> Unit)?,
    ): StationSeriesBuildResult {
        val series = mutableMapOf<String, List<StationTimeSeriesPoint>>()
        val stats = mutableListOf<StationSeriesStats>()
        val forecastSeries = hourlyForecastSeries(hourlyForecasts, displaySource)
        val allowForecastExtrapolation = displaySource == WeatherSource.NWS

        observations
            .groupBy { it.stationId }
            .forEach { (stationId, rows) ->
                val sorted = rows.sortedBy { it.timestamp }
                val points = mutableListOf<StationTimeSeriesPoint>()
                var observedPointCount = 0
                var interpolatedPointCount = 0

                for (index in sorted.indices) {
                    val current = sorted[index]
                    points += StationTimeSeriesPoint(
                        timestamp = current.timestamp,
                        temperature = current.temperature,
                        stationId = stationId,
                        stationName = current.stationName,
                        distanceKm = current.distanceKm,
                        stationType = current.stationType,
                        sourceKind = "observed",
                        anchorTimestamp = current.timestamp,
                    )
                    observedPointCount += 1

                    if (index == sorted.lastIndex) continue
                    val next = sorted[index + 1]
                    val gapMs = next.timestamp - current.timestamp
                    if (gapMs <= interpolationStepMs) continue

                    if (gapMs <= maxInterpolationGapMs) {
                        var interpolatedTimestamp = current.timestamp + interpolationStepMs
                        while (interpolatedTimestamp < next.timestamp) {
                            val fraction = (interpolatedTimestamp - current.timestamp).toFloat() / gapMs.toFloat()
                            val interpolated = current.temperature + (next.temperature - current.temperature) * fraction
                            if (onBlendDebug != null) {
                                onBlendDebug.invoke {
                                    "station_interpolate station=$stationId at=${
                                        Instant.ofEpochMilli(interpolatedTimestamp)
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                                    } temp=${String.format("%.1f", interpolated)} from=${
                                        Instant.ofEpochMilli(current.timestamp)
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                                    }..${
                                        Instant.ofEpochMilli(next.timestamp)
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                                    }"
                                }
                            }
                            points += StationTimeSeriesPoint(
                                timestamp = interpolatedTimestamp,
                                temperature = interpolated,
                                stationId = stationId,
                                stationName = current.stationName,
                                distanceKm = current.distanceKm,
                                stationType = current.stationType,
                                sourceKind = "interpolated",
                                anchorTimestamp = next.timestamp,
                            )
                            interpolatedPointCount += 1
                            interpolatedTimestamp += interpolationStepMs
                        }
                    } else {
                        if (onBlendDebug != null) {
                            onBlendDebug.invoke {
                                "station_gap station=$stationId gapMin=${gapMs / 60000} from=${
                                    Instant.ofEpochMilli(current.timestamp)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                }..${
                                    Instant.ofEpochMilli(next.timestamp)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                }"
                            }
                        }
                    }
                }

                val last = sorted.lastOrNull()
                val extrapolatedPointCountBefore = points.count { it.sourceKind == "forecast_extrapolated" }
                if (allowForecastExtrapolation && last != null) {
                    addForecastGuidedExtrapolatedPoints(
                        stationId = stationId,
                        lastObservation = last,
                        forecastSeries = forecastSeries,
                        interpolationStepMs = interpolationStepMs,
                        maxExtrapolationGapMs = maxExtrapolationGapMs,
                        endMs = endMs,
                        points = points,
                        onBlendDebug = onBlendDebug,
                    )
                }

                val sortedPoints = points.sortedBy { it.timestamp }
                val extrapolatedPointCount = sortedPoints.count { it.sourceKind == "forecast_extrapolated" } - extrapolatedPointCountBefore
                series[stationId] = sortedPoints
                stats += StationSeriesStats(
                    stationId = stationId,
                    rawObservationCount = sorted.size,
                    observedPointCount = observedPointCount,
                    interpolatedPointCount = interpolatedPointCount,
                    extrapolatedPointCount = extrapolatedPointCount,
                    outputPointCount = sortedPoints.size,
                    firstTimestamp = sortedPoints.firstOrNull()?.timestamp,
                    lastTimestamp = sortedPoints.lastOrNull()?.timestamp,
                )
            }
        return StationSeriesBuildResult(series = series, stats = stats.sortedBy { it.stationId })
    }

    private fun addForecastGuidedExtrapolatedPoints(
        stationId: String,
        lastObservation: ObservationEntity,
        forecastSeries: List<HourlyForecastEntity>,
        interpolationStepMs: Long,
        maxExtrapolationGapMs: Long,
        endMs: Long,
        points: MutableList<StationTimeSeriesPoint>,
        onBlendDebug: ((() -> String) -> Unit)?,
    ) {
        val baseForecastTemp = forecastTemperatureAt(forecastSeries, lastObservation.timestamp) ?: return
        val maxTimestamp = Math.min(lastObservation.timestamp + maxExtrapolationGapMs, endMs)
        var extrapolatedTimestamp = lastObservation.timestamp + interpolationStepMs
        while (extrapolatedTimestamp <= maxTimestamp) {
            val targetForecastTemp = forecastTemperatureAt(forecastSeries, extrapolatedTimestamp) ?: break
            val extrapolated = lastObservation.temperature + (targetForecastTemp - baseForecastTemp)
            if (onBlendDebug != null) {
                onBlendDebug.invoke {
                    "station_extrapolate station=$stationId at=${
                        Instant.ofEpochMilli(extrapolatedTimestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                    } temp=${String.format("%.1f", extrapolated)} fromObs=${
                        Instant.ofEpochMilli(lastObservation.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                    } forecastDelta=${String.format("%.1f", targetForecastTemp - baseForecastTemp)}"
                }
            }
            points += StationTimeSeriesPoint(
                timestamp = extrapolatedTimestamp,
                temperature = extrapolated,
                stationId = stationId,
                stationName = lastObservation.stationName,
                distanceKm = lastObservation.distanceKm,
                stationType = lastObservation.stationType,
                sourceKind = "forecast_extrapolated",
                anchorTimestamp = lastObservation.timestamp,
            )
            extrapolatedTimestamp += interpolationStepMs
        }
    }

    private fun hourlyForecastSeries(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): List<HourlyForecastEntity> =
        hourlyForecasts
            .groupBy { it.dateTime }
            .mapNotNull { (_, rows) ->
                val preferred = rows.find { it.source == displaySource.id }
                val gap = rows.find { it.source == WeatherSource.GENERIC_GAP.id }
                val fallback = rows.firstOrNull()
                preferred ?: gap ?: fallback
            }
            .sortedBy { it.dateTime }

    private fun forecastTemperatureAt(
        forecastSeries: List<HourlyForecastEntity>,
        targetTimestamp: Long,
    ): Float? {
        if (forecastSeries.isEmpty()) return null
        val zoneId = ZoneId.systemDefault()
        val targetTime = Instant.ofEpochMilli(targetTimestamp).atZone(zoneId).toLocalDateTime()
        val exact = forecastSeries.find { forecastDateTime(it) == targetTime }
        if (exact != null) return exact.temperature

        val before = forecastSeries.lastOrNull { !forecastDateTime(it).isAfter(targetTime) }
        val after = forecastSeries.firstOrNull { !forecastDateTime(it).isBefore(targetTime) }
        if (before == null || after == null) return null
        val beforeDateTime = forecastDateTime(before)
        val afterDateTime = forecastDateTime(after)
        if (beforeDateTime == afterDateTime) return before.temperature

        val totalSeconds = java.time.Duration.between(beforeDateTime, afterDateTime).seconds
        if (totalSeconds <= 0L) return null
        val elapsedSeconds = java.time.Duration.between(beforeDateTime, targetTime).seconds
        val fraction = elapsedSeconds.toFloat() / totalSeconds.toFloat()
        return before.temperature + (after.temperature - before.temperature) * fraction
    }

    private fun forecastDateTime(forecast: HourlyForecastEntity): LocalDateTime =
        Instant.ofEpochMilli(forecast.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private fun resolveStationPointForTimestamp(
        points: List<StationTimeSeriesPoint>,
        targetTs: Long,
        windowMs: Long,
    ): StationTimeSeriesPoint? {
        val exactOrNearest = points.minByOrNull { Math.abs(it.timestamp - targetTs) } ?: return null
        return if (Math.abs(exactOrNearest.timestamp - targetTs) <= windowMs) exactOrNearest else null
    }

    private fun matchesObservationSource(
        observation: ObservationEntity,
        displaySource: WeatherSource,
    ): Boolean {
        val inferred = ObservationResolver.inferSource(observation.stationId)
        return inferred == displaySource.id || inferred == WeatherSource.GENERIC_GAP.id
    }
}
