package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ActualTemperaturePoint(
    val timeMs: Long,
    val forecastTemp: Float,
    val actualTemp: Float?,
    val isActual: Boolean,
    val isObservedActual: Boolean,
)

data class SelectedObservationSeries(
    val stationId: String?,
    val stationName: String?,
    val stationType: String?,
    val observations: List<ObservationReading>,
    val rejectedGroupCount: Int,
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
    val observations: List<ObservationReading>,
    val stats: BlendObservationStats,
)

data class ActualTemperatureSeriesResult(
    val points: List<ActualTemperaturePoint>,
    val blendStats: BlendObservationStats?,
    val selectedStationId: String?,
    val sourceObservationCount: Int,
    val blendInputCount: Int,
)

object ActualTemperatureSeriesBuilder {
    private const val GENERIC_GAP_SOURCE = "Generic"
    private const val TIME_DECAY_MAX_AGE_MS = 3 * 60 * 60 * 1000L
    private const val NEAR_ZERO_KM = 0.1f
    private const val MAX_INTERPOLATION_GAP_MS = 3 * 60 * 60 * 1000L
    private const val MAX_EXTRAPOLATION_GAP_MS = 3 * 60 * 60 * 1000L

    fun build(
        hourlyForecasts: List<HourlyForecast>,
        observations: List<ObservationReading>,
        centerTime: LocalDateTime,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        backHours: Long,
        forwardHours: Long,
        contextLookbackHours: Long,
        contextLookaheadHours: Long,
        now: LocalDateTime = LocalDateTime.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        smoothedForecasts: Map<Long, Float>? = null,
        onBlendDebug: ((() -> String) -> Unit)? = null,
    ): ActualTemperatureSeriesResult {
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySourceId, now.atZone(zoneId).toInstant().toEpochMilli())
        val truncated = centerTime.truncatedTo(ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(backHours)
        val endHour = alignedCenter.plusHours(forwardHours)
        val startMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
        val contextStartMs = alignedCenter.minusHours(contextLookbackHours).atZone(zoneId).toInstant().toEpochMilli()
        val contextEndMs = alignedCenter.plusHours(contextLookaheadHours).atZone(zoneId).toInstant().toEpochMilli()

        val sourceActuals = observations.filter { matchesObservationSource(it, displaySourceId) }
        val selectedStationId =
            if (displaySourceId != WeatherSource.NWS.id) {
                selectObservationSeries(
                    observations = sourceActuals,
                    displaySourceId = displaySourceId,
                    startHour = alignedCenter.minusHours(contextLookbackHours),
                    endHour = alignedCenter.plusHours(contextLookaheadHours),
                    zoneId = zoneId,
                ).stationId
            } else {
                null
            }
        val blendInputActuals =
            if (selectedStationId != null) {
                sourceActuals.filter { it.stationId == selectedStationId }
            } else {
                sourceActuals
            }

        val blendedActualsResult = blendObservationSeries(
            observations = blendInputActuals,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            startMs = contextStartMs,
            endMs = contextEndMs,
            onBlendDebug = onBlendDebug,
        )
        val blendedActuals = blendedActualsResult.observations

        val topHourPoints = mutableListOf<ActualTemperaturePoint>()
        var currentHour = startHour
        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]
            topHourPoints += ActualTemperaturePoint(
                timeMs = hourMs,
                forecastTemp = smoothedForecasts?.get(hourMs) ?: forecast?.temperature ?: Float.NaN,
                actualTemp = null,
                isActual = false,
                isObservedActual = false,
            )
            currentHour = currentHour.plusHours(1)
        }

        val actualByTime = mutableMapOf<Long, ObservationReading>()
        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()
        blendedActuals.forEach { obs ->
            val obsTime = Instant.ofEpochMilli(obs.timestamp).atZone(zoneId).toLocalDateTime()
            if (!obsTime.isBefore(startHour) && !obsTime.isAfter(endHour) && obsTime.isBefore(now)) {
                actualByTime[obs.timestamp] = obs
            }
        }

        val topHoursByMs = topHourPoints.associateBy { it.timeMs }
        val allTimes = (topHoursByMs.keys + actualByTime.keys).sorted()
        val points = mutableListOf<ActualTemperaturePoint>()

        for (timeMs in allTimes) {
            val topHour = topHoursByMs[timeMs]
            val actual = actualByTime[timeMs]
            val actualTemp = actual?.temperature
            val isPast = timeMs < nowMs
            if (topHour != null) {
                points += topHour.copy(
                    isActual = isPast && actualTemp != null,
                    actualTemp = actualTemp,
                    isObservedActual = isPast && actual?.condition == "observed",
                )
            } else {
                points += ActualTemperaturePoint(
                    timeMs = timeMs,
                    forecastTemp = interpolateForecastTemp(topHourPoints, timeMs),
                    actualTemp = actualTemp,
                    isActual = true,
                    isObservedActual = actual?.condition == "observed",
                )
            }
        }

        var lastActual: Float? = blendedActuals
            .filter { it.timestamp < startMs && it.timestamp <= nowMs }
            .lastOrNull()
            ?.temperature

        val carried = points.map { point ->
            if (point.isActual && point.actualTemp != null) {
                lastActual = point.actualTemp
                point
            } else if (point.timeMs < nowMs) {
                if (lastActual != null) {
                    point.copy(isActual = true, actualTemp = lastActual, isObservedActual = false)
                } else {
                    point.copy(isActual = false, actualTemp = null, isObservedActual = false)
                }
            } else {
                point
            }
        }

        return ActualTemperatureSeriesResult(
            points = carried,
            blendStats = blendedActualsResult.stats,
            selectedStationId = selectedStationId,
            sourceObservationCount = sourceActuals.size,
            blendInputCount = blendInputActuals.size,
        )
    }

    fun blendObservationSeries(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        startMs: Long,
        endMs: Long,
        onBlendDebug: ((() -> String) -> Unit)? = null,
    ): BlendObservationResult {
        val filtered = observations
            .filter { matchesObservationSource(it, displaySourceId) }
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

        val forecastSeries = hourlyForecastSeries(hourlyForecasts, displaySourceId)
        val byStation = filtered.groupBy { it.stationId }.mapValues { it.value.sortedBy { obs -> obs.timestamp } }
        val candidateTimes = filtered.map { it.timestamp }.distinct().sorted()
        val result = mutableListOf<ObservationReading>()
        val candidates = mutableListOf<DecayBlendInput>()
        val zoneId = if (onBlendDebug != null) ZoneId.systemDefault() else null
        val timePattern = if (onBlendDebug != null) DateTimeFormatter.ofPattern("HH:mm") else null

        // Blend once per distinct observation timestamp (no time-bucket thinning). Observation data is
        // naturally sparse (NWS ~15 min, other sources hourly), so this is cheap, and — crucially — it
        // makes the emitted series independent of the query window. That is what lets the daily bar
        // (ActualsAggregator) and the hourly graph (build()) derive the SAME max/min for a day instead
        // of two window-dependent 5-min-thinned approximations. See daily_vs_hourly_actual_extrema_mismatch.
        for (targetTs in candidateTimes) {
            candidates.clear()
            var hasObserved = false
            var hasInterpolated = false
            var bestAnchorTs = -1L
            var anchorStation: ObservationReading? = null

            for ((_, stationObs) in byStation) {
                val resolved = resolveStationValueAt(stationObs, targetTs, forecastSeries)
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
            val blendedTemp = blendCandidateTemperature(candidates) ?: continue
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
                    anchor.copy(
                        timestamp = targetTs,
                        temperature = blendedTemp,
                        condition = bestSourceKind,
                        locationLat = userLat,
                        locationLon = userLon,
                        api = displaySourceId,
                    ),
                )
            }
        }

        return BlendObservationResult(
            observations = result,
            stats = BlendObservationStats(
                rawObservationCount = observations.size,
                filteredObservationCount = filtered.size,
                stationCount = byStation.size,
                candidateTimeCount = candidateTimes.size,
                emittedPointCount = result.size,
                dedupSkippedCount = 0, // thinning removed; retained as 0 for stat/summary compatibility
            ),
        )
    }

    fun selectObservationSeries(
        observations: List<ObservationReading>,
        displaySourceId: String,
        startHour: LocalDateTime,
        endHour: LocalDateTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SelectedObservationSeries {
        val sourceObservations = observations.filter { matchesObservationSource(it, displaySourceId) }
        if (sourceObservations.isEmpty()) {
            return SelectedObservationSeries(null, null, null, emptyList(), 0)
        }

        val grouped = sourceObservations.groupBy { it.stationId }
        val selectedEntry = grouped.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<ObservationReading>>>(
                { entry -> entry.value.map { observationHour(it, zoneId) }.toSet().size },
                { entry -> entry.value.size },
                { entry -> -entry.value.minOfOrNull { it.distanceKm }!! },
                { entry -> entry.value.maxOf { it.timestamp } },
                { entry -> -entry.key.hashCode() },
            ),
        )

        val chosen = selectedEntry?.value.orEmpty().sortedBy { it.timestamp }
        val metadata = chosen.firstOrNull()
        return SelectedObservationSeries(
            stationId = selectedEntry?.key,
            stationName = metadata?.stationName,
            stationType = metadata?.stationType,
            observations = chosen.filter { obs ->
                val obsTime = Instant.ofEpochMilli(obs.timestamp).atZone(zoneId).toLocalDateTime()
                !obsTime.isBefore(startHour) && !obsTime.isAfter(endHour)
            },
            rejectedGroupCount = (grouped.size - 1).coerceAtLeast(0),
        )
    }

    private data class DecayBlendInput(
        val distanceKm: Float,
        val temperature: Float,
        val ageMs: Long,
    )

    private data class ResolvedStationValue(
        val temperature: Float,
        val distanceKm: Float,
        val sourceKind: String,
        val anchorTs: Long,
    )

    private fun blendCandidateTemperature(candidates: List<DecayBlendInput>): Float? {
        val veryClose = candidates.filter { it.distanceKm <= NEAR_ZERO_KM && timeDecayFactor(it.ageMs) > 0f }
        if (veryClose.isNotEmpty()) return veryClose.minBy { it.distanceKm }.temperature

        var wSum = 0.0
        var tSum = 0.0
        for (candidate in candidates) {
            val decay = timeDecayFactor(candidate.ageMs)
            if (decay <= 0f) continue
            val w = decay.toDouble() / (candidate.distanceKm.toDouble() * candidate.distanceKm.toDouble())
            tSum += w * candidate.temperature
            wSum += w
        }
        return if (wSum > 0.0) (tSum / wSum).toFloat() else null
    }

    private fun resolveStationValueAt(
        stationObs: List<ObservationReading>,
        targetTs: Long,
        forecastSeries: List<HourlyForecast>,
    ): ResolvedStationValue? {
        if (stationObs.isEmpty()) return null
        val distanceKm = stationObs.first().distanceKm
        val insertIdx = stationObs.binarySearch { it.timestamp.compareTo(targetTs) }.let {
            if (it >= 0) {
                return ResolvedStationValue(stationObs[it].temperature, distanceKm, "observed", stationObs[it].timestamp)
            }
            -(it + 1)
        }

        val before = if (insertIdx > 0) stationObs[insertIdx - 1] else null
        val after = if (insertIdx < stationObs.size) stationObs[insertIdx] else null

        return when {
            before != null && after != null -> {
                val gapMs = after.timestamp - before.timestamp
                if (gapMs > MAX_INTERPOLATION_GAP_MS) return null
                val fraction = (targetTs - before.timestamp).toFloat() / gapMs.toFloat()
                val interpolated = before.temperature + (after.temperature - before.temperature) * fraction
                ResolvedStationValue(interpolated, distanceKm, "interpolated", after.timestamp)
            }
            before != null && after == null -> {
                val gapMs = targetTs - before.timestamp
                if (gapMs > MAX_EXTRAPOLATION_GAP_MS) return null
                val baseForecast = forecastTemperatureAt(forecastSeries, before.timestamp) ?: return null
                val targetForecast = forecastTemperatureAt(forecastSeries, targetTs) ?: return null
                val extrapolated = before.temperature + (targetForecast - baseForecast)
                ResolvedStationValue(extrapolated, distanceKm, "forecast_extrapolated", before.timestamp)
            }
            else -> null
        }
    }

    private fun interpolateForecastTemp(topHourPoints: List<ActualTemperaturePoint>, timeMs: Long): Float {
        val prev = topHourPoints.lastOrNull { it.timeMs <= timeMs }
        val next = topHourPoints.firstOrNull { it.timeMs > timeMs }
        return if (prev != null && next != null) {
            val prevT = prev.forecastTemp
            val nextT = next.forecastTemp
            if (prevT.isNaN() || nextT.isNaN()) {
                Float.NaN
            } else {
                val totalMs = next.timeMs - prev.timeMs
                val fraction = (timeMs - prev.timeMs).toFloat() / totalMs.toFloat()
                prevT + (nextT - prevT) * fraction
            }
        } else {
            listOfNotNull(prev?.forecastTemp, next?.forecastTemp).firstOrNull { !it.isNaN() } ?: Float.NaN
        }
    }

    private fun hourlyForecastSeries(hourlyForecasts: List<HourlyForecast>, displaySourceId: String): List<HourlyForecast> =
        hourlyForecasts
            .groupBy { it.dateTime }
            .mapNotNull { (_, rows) ->
                rows.find { it.source == displaySourceId }
                    ?: rows.find { it.source == GENERIC_GAP_SOURCE }
                    ?: rows.firstOrNull()
            }
            .sortedBy { it.dateTime }

    private fun resolveForecastsByTime(
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        nowMs: Long,
    ): Map<Long, HourlyForecast> =
        hourlyForecasts
            .groupBy { it.dateTime }
            .mapNotNull { (time, rows) ->
                val candidates = when {
                    rows.any { it.source == displaySourceId } -> rows.filter { it.source == displaySourceId }
                    rows.any { it.source == GENERIC_GAP_SOURCE } -> rows.filter { it.source == GENERIC_GAP_SOURCE }
                    else -> rows
                }
                // GPS drift causes same source+timestamp rows at different lat/lon. Prefer earliest
                // fetchedAt for past hours (original forecast) and latest for future hours.
                val selected = if (time < nowMs) candidates.minByOrNull { it.fetchedAt }
                               else candidates.maxByOrNull { it.fetchedAt }
                selected?.let { time to it }
            }
            .toMap()

    private fun forecastTemperatureAt(forecastSeries: List<HourlyForecast>, targetTs: Long): Float? {
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

    private fun timeDecayFactor(ageMs: Long): Float {
        if (ageMs <= 0L) return 1.0f
        if (ageMs >= TIME_DECAY_MAX_AGE_MS) return 0.0f
        return 1.0f - (ageMs.toFloat() / TIME_DECAY_MAX_AGE_MS.toFloat())
    }

    private fun matchesObservationSource(observation: ObservationReading, displaySourceId: String): Boolean =
        (observation.api == displaySourceId || observation.api == GENERIC_GAP_SOURCE) && 
        observation.stationId != "NWS_BLEND"

    private fun observationHour(observation: ObservationReading, zoneId: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(observation.timestamp).atZone(zoneId).toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
}
