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
 * Utility for blending multi-station weather observations using Inverse Distance Weighting (IDW).
 *
 * Candidate timestamps are driven by when stations actually reported, not a synthetic grid.
 * For each candidate time:
 *  - Stations with a real observation near that time contribute directly
 *  - Stations with bracketing observations (historical gap) contribute via interpolation
 *  - Stations whose last observation is older contribute via forecast-guided extrapolation
 */
object ObservationBlender {

    private const val TIME_DECAY_MAX_AGE_MS = 3 * 60 * 60 * 1000L
    private const val NEAR_ZERO_KM = 0.1f

    private fun timeDecayFactor(ageMs: Long): Float {
        if (ageMs <= 0L) return 1.0f
        if (ageMs >= TIME_DECAY_MAX_AGE_MS) return 0.0f
        return 1.0f - (ageMs.toFloat() / TIME_DECAY_MAX_AGE_MS.toFloat())
    }

    private data class DecayBlendInput(
        val distanceKm: Float,
        val temperature: Float,
        val ageMs: Long,
    )

    data class BlendObservationStats(
        val rawObservationCount: Int,
        val filteredObservationCount: Int,
        val stationCount: Int,
        val candidateTimeCount: Int,
        val emittedPointCount: Int,
        val dedupSkippedCount: Int,
    ) {
        fun summary(): String =
            "rawObs=$rawObservationCount filteredObs=$filteredObservationCount stations=$stationCount " +
                "candidateTimes=$candidateTimeCount emitted=$emittedPointCount dedupSkipped=$dedupSkippedCount"
    }

    data class BlendObservationResult(
        val observations: List<ObservationEntity>,
        val stats: BlendObservationStats,
    )

    /**
     * Resolves the current observed temperature by blending the latest station observations.
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

        val contextStartMs = alignedCenter.minusHours(com.weatherwidget.widget.WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val contextEndMs = alignedCenter.plusHours(com.weatherwidget.widget.WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()

        val result = blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            userLat = userLat,
            userLon = userLon,
            startMs = contextStartMs,
            endMs = contextEndMs,
            onBlendDebug = null,
        )

        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()
        val pastBlended = result.observations.filter { it.timestamp <= nowMs }

        val latestObs = pastBlended
            .filter { it.condition == "observed" }
            .maxByOrNull { it.timestamp }
            ?: pastBlended
                .filter { it.condition == "interpolated" }
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
                ),
            )
        }

        val dedupMs = 5 * 60 * 1000L
        val maxInterpolationGapMs = 3 * 60 * 60 * 1000L
        val maxExtrapolationGapMs = 3 * 60 * 60 * 1000L

        val forecastSeries = hourlyForecastSeries(hourlyForecasts, displaySource)

        // Group observations by station, sorted by time
        val byStation: Map<String, List<ObservationEntity>> = filtered
            .groupBy { it.stationId }
            .mapValues { it.value.sortedBy { obs -> obs.timestamp } }

        // Candidate times = all real observation timestamps across all stations
        val candidateTimes = filtered
            .map { it.timestamp }
            .distinct()
            .sorted()

        val result = mutableListOf<ObservationEntity>()
        var lastEmittedMs = 0L
        var dedupSkippedCount = 0

        val zoneId = if (onBlendDebug != null) ZoneId.systemDefault() else null
        val timePattern = if (onBlendDebug != null) DateTimeFormatter.ofPattern("HH:mm") else null
        val candidates = mutableListOf<DecayBlendInput>()

        for (targetTs in candidateTimes) {
            if (targetTs - lastEmittedMs < dedupMs) {
                dedupSkippedCount += 1
                continue
            }

            candidates.clear()
            var hasObserved = false
            var hasInterpolated = false
            var bestAnchorTs = -1L
            var anchorStation: ObservationEntity? = null

            for ((_, stationObs) in byStation) {
                val resolved = resolveStationValueAt(
                    stationObs = stationObs,
                    targetTs = targetTs,
                    maxInterpolationGapMs = maxInterpolationGapMs,
                    maxExtrapolationGapMs = maxExtrapolationGapMs,
                    forecastSeries = forecastSeries,
                )
                if (resolved != null) {
                    val ageMs = maxOf(0L, targetTs - resolved.anchorTs)
                    candidates.add(DecayBlendInput(resolved.distanceKm, resolved.temperature, ageMs))
                    if (resolved.anchorTs > bestAnchorTs) bestAnchorTs = resolved.anchorTs
                    if (anchorStation == null) anchorStation = stationObs.minByOrNull { it.distanceKm }
                    when (resolved.sourceKind) {
                        "observed" -> hasObserved = true
                        "interpolated" -> hasInterpolated = true
                    }
                }
            }

            if (candidates.isEmpty()) continue

            val anchor = anchorStation ?: continue

            val veryClose = candidates.filter { it.distanceKm <= NEAR_ZERO_KM && timeDecayFactor(it.ageMs) > 0f }
            val blendedTemp: Float? = when {
                veryClose.isNotEmpty() -> veryClose.minBy { it.distanceKm }.temperature
                else -> {
                    var wSum = 0.0
                    var tSum = 0.0
                    for (c in candidates) {
                        val decay = timeDecayFactor(c.ageMs)
                        if (decay <= 0f) continue
                        val w = decay.toDouble() / (c.distanceKm.toDouble() * c.distanceKm.toDouble())
                        tSum += w * c.temperature
                        wSum += w
                    }
                    if (wSum > 0.0) (tSum / wSum).toFloat() else null
                }
            }

            if (blendedTemp == null) continue

            val bestSourceKind = when {
                hasObserved -> "observed"
                hasInterpolated -> "interpolated"
                else -> "forecast_extrapolated"
            }

            if (onBlendDebug != null && zoneId != null && timePattern != null) {
                val loopTs = targetTs
                val loopCandidates = candidates.toList()
                val loopTemp = blendedTemp
                onBlendDebug.invoke {
                    val timeStr = Instant.ofEpochMilli(loopTs).atZone(zoneId).format(timePattern)
                    if (loopCandidates.size == 1) {
                        "emit t=$timeStr single_station temp=${String.format("%.1f", loopTemp)} source=$bestSourceKind"
                    } else {
                        "emit t=$timeStr blended=${String.format("%.1f", loopTemp)} stationCount=${loopCandidates.size} source=$bestSourceKind"
                    }
                }
            }

            if (targetTs in startMs..endMs) {
                result.add(
                    ObservationEntity(
                        stationId = anchor.stationId,
                        stationName = anchor.stationName,
                        timestamp = targetTs,
                        temperature = blendedTemp,
                        condition = bestSourceKind,
                        locationLat = userLat,
                        locationLon = userLon,
                        distanceKm = anchor.distanceKm,
                        stationType = anchor.stationType,
                        api = displaySource.id,
                        fetchedAt = if (bestAnchorTs > 0) bestAnchorTs else anchor.fetchedAt,
                    ),
                )
            }
            lastEmittedMs = targetTs
        }

        return BlendObservationResult(
            observations = result,
            stats = BlendObservationStats(
                rawObservationCount = observations.size,
                filteredObservationCount = filtered.size,
                stationCount = byStation.size,
                candidateTimeCount = candidateTimes.size,
                emittedPointCount = result.size,
                dedupSkippedCount = dedupSkippedCount,
            ),
        )
    }

    private data class ResolvedStationValue(
        val temperature: Float,
        val distanceKm: Float,
        val sourceKind: String,
        val anchorTs: Long,
    )

    /**
     * Resolves a station's temperature at a target timestamp.
     *
     * - Exact match: use directly (observed)
     * - Target is between two observations: interpolate (interpolated)
     * - Target is after last observation: forward-extrapolate using forecast curve delta (forecast_extrapolated)
     * - No usable data within gap limits: null
     */
    private fun resolveStationValueAt(
        stationObs: List<ObservationEntity>,
        targetTs: Long,
        maxInterpolationGapMs: Long,
        maxExtrapolationGapMs: Long,
        forecastSeries: List<HourlyForecastEntity>,
    ): ResolvedStationValue? {
        if (stationObs.isEmpty()) return null

        val distanceKm = stationObs.first().distanceKm

        // Binary search for position in sorted list
        val insertIdx = stationObs.binarySearch { it.timestamp.compareTo(targetTs) }.let {
            if (it >= 0) return ResolvedStationValue(
                temperature = stationObs[it].temperature,
                distanceKm = distanceKm,
                sourceKind = "observed",
                anchorTs = stationObs[it].timestamp,
            )
            -(it + 1)
        }

        val before = if (insertIdx > 0) stationObs[insertIdx - 1] else null
        val after = if (insertIdx < stationObs.size) stationObs[insertIdx] else null

        return when {
            // Historical gap: interpolate between bracketing observations
            before != null && after != null -> {
                val gapMs = after.timestamp - before.timestamp
                if (gapMs > maxInterpolationGapMs) return null
                val fraction = (targetTs - before.timestamp).toFloat() / gapMs.toFloat()
                val interpolated = before.temperature + (after.temperature - before.temperature) * fraction
                ResolvedStationValue(
                    temperature = interpolated,
                    distanceKm = distanceKm,
                    sourceKind = "interpolated",
                    anchorTs = after.timestamp,
                )
            }

            // Current/latest: forward-extrapolate from last observation using forecast trend
            before != null && after == null -> {
                val gapMs = targetTs - before.timestamp
                if (gapMs > maxExtrapolationGapMs) return null
                val baseForecast = forecastTemperatureAt(forecastSeries, before.timestamp) ?: return null
                val targetForecast = forecastTemperatureAt(forecastSeries, targetTs) ?: return null
                val extrapolated = before.temperature + (targetForecast - baseForecast)
                ResolvedStationValue(
                    temperature = extrapolated,
                    distanceKm = distanceKm,
                    sourceKind = "forecast_extrapolated",
                    anchorTs = before.timestamp,
                )
            }

            // Target is before this station's first observation — skip
            else -> null
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
        targetTs: Long,
    ): Float? {
        if (forecastSeries.isEmpty()) return null

        val exactIdx = forecastSeries.binarySearch { it.dateTime.compareTo(targetTs) }
        if (exactIdx >= 0) return forecastSeries[exactIdx].temperature

        val insertIdx = -(exactIdx + 1)
        if (insertIdx == 0 || insertIdx >= forecastSeries.size) return null

        val before = forecastSeries[insertIdx - 1]
        val after = forecastSeries[insertIdx]

        val totalMs = after.dateTime - before.dateTime
        if (totalMs <= 0L) return before.temperature

        val elapsedMs = targetTs - before.dateTime
        val fraction = elapsedMs.toFloat() / totalMs.toFloat()
        return before.temperature + (after.temperature - before.temperature) * fraction
    }

    private fun matchesObservationSource(
        observation: ObservationEntity,
        displaySource: WeatherSource,
    ): Boolean {
        return observation.api == displaySource.id || observation.api == WeatherSource.GENERIC_GAP.id
    }
}
