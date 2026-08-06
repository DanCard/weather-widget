package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ObservationOrigin
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.shared.util.Log
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
    val loneStationSkippedCount: Int = 0,
    /** Candidate timestamps where a synthetic `<SOURCE>_MAIN` backfill row was outranked by real stations. */
    val syntheticDeprioritizedCount: Int = 0,
) {
    fun summary(): String =
        "rawObs=$rawObservationCount filteredObs=$filteredObservationCount stations=$stationCount " +
            "candidateTimes=$candidateTimeCount emitted=$emittedPointCount dedupSkipped=$dedupSkippedCount " +
            "loneSkipped=$loneStationSkippedCount syntheticDeprioritized=$syntheticDeprioritizedCount"
}

/**
 * One station's contribution to a single blended point, as it was actually weighted — the row behind
 * the "Blend" tab's table.
 *
 * [rawTemp] is what the station measured at [lastReadingMs]; [resolvedTemp] is what was fed to the
 * blend. They differ whenever [sourceKind] is not `observed`, and the gap is pure forecast: a stale
 * station is carried forward by the forecast's change over the gap (see [extrapolateForward]). On a
 * fast-warming morning that gap reaches several degrees, which is how a blended "observed" value ends
 * up above every reading in the stations list.
 */
data class BlendContribution(
    val stationId: String,
    val stationName: String,
    val stationType: String,
    val distanceKm: Float,
    val lastReadingMs: Long,
    val rawTemp: Float,
    val resolvedTemp: Float,
    val sourceKind: String,
    val ageMs: Long,
    val weight: Double,
    val weightShare: Double,
)

/**
 * The highest final-weight contribution behind one emitted blend point.
 *
 * This is the compact render-path counterpart to [BlendBreakdown]: callers that need only the
 * station explaining the displayed current temperature do not retain every contribution for every
 * point. Reuses [BlendContribution] so the daily overlay and Blend tab share one field set.
 */
data class DominantBlend(
    val targetMs: Long,
    val contribution: BlendContribution,
)

/** Every contribution behind one emitted point, for diagnostics only. */
data class BlendBreakdown(
    val targetMs: Long,
    val blendedTemp: Float,
    val sourceKind: String,
    val contributions: List<BlendContribution>,
)

data class BlendObservationResult(
    val observations: List<ObservationReading>,
    val stats: BlendObservationStats,
    /** Most-recent-first, capped by `captureBreakdowns`. Empty unless capture was requested. */
    val breakdowns: List<BlendBreakdown> = emptyList(),
    /** Latest dominant contribution at/before the requested cutoff; null unless requested. */
    val latestDominantContribution: DominantBlend? = null,
)

data class ActualTemperatureSeriesResult(
    val points: List<ActualTemperaturePoint>,
    val blendStats: BlendObservationStats?,
    val selectedStationId: String?,
    val sourceObservationCount: Int,
    val blendInputCount: Int,
)

object ActualTemperatureSeriesBuilder {
    private const val TAG = "ActualTempSeries"
    private const val GENERIC_GAP_SOURCE = "Generic"
    // The stations list badges a station "Stale" at exactly this age, so the two must not drift apart.
    private const val TIME_DECAY_MAX_AGE_MS = ObservationOrigin.BLEND_MAX_AGE_MS
    private const val NEAR_ZERO_KM = 0.1f
    private const val MAX_INTERPOLATION_GAP_MS = 3 * 60 * 60 * 1000L
    private const val MAX_EXTRAPOLATION_GAP_MS = 3 * 60 * 60 * 1000L

    // Personal weather stations (backyard units) frequently over-read in sun and are poorly sited.
    // Callers pass a `personalStationWeight` multiplier (0.0..1.0) applied to a PWS's IDW weight
    // relative to an official station at the same distance. 1.0 (the default) = no discount, equal
    // weight; 0.0 = ignore PWS entirely. The value originates from a user setting on each platform.
    private const val PERSONAL_STATION_TYPE = "PERSONAL"

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
        personalStationWeight: Double = 1.0,
        onBlendDebug: ((() -> String) -> Unit)? = null,
    ): ActualTemperatureSeriesResult {
        val forecastsByTime = HourlyForecastSelector.selectForecastsByTime(hourlyForecasts, displaySourceId, userLat, userLon)
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
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
            onBlendDebug = onBlendDebug,
        )
        val blendedActuals = blendedActualsResult.observations

        val topHourPoints = mutableListOf<ActualTemperaturePoint>()
        var currentHour = startHour
        while (currentHour.isBefore(endHour)) {
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
        personalStationWeight: Double = 1.0,
        zoneId: ZoneId = ZoneId.systemDefault(),
        onBlendDebug: ((() -> String) -> Unit)? = null,
        /**
         * Retain the per-station contribution table for up to this many of the most recent emitted
         * points (0 = off). Off on every render path — this runs on each minute tick — and set only by
         * the diagnostics UI. Capture is observationally inert: it never changes the emitted series.
         */
        captureBreakdowns: Int = 0,
        /**
         * Capture only the dominant contribution for the latest emitted point at or before this
         * timestamp. Unlike [captureBreakdowns], this is intentionally cheap enough for the gated
         * large-daily render path and never retains a full contribution table.
         */
        captureLatestDominantAtOrBeforeMs: Long? = null,
    ): BlendObservationResult {
        // TOTAL order, not `sortedBy { it.timestamp }`. That is a STABLE sort, so rows sharing a
        // timestamp kept the CALLER's order — and the blend is order-sensitive: `groupBy { stationId }`
        // below inherits this order, so byStation's iteration order decides dominantStationByDay's
        // maxWith tie-break (which gates the lone-station skip) and anchorStation ("first that
        // resolves"). Several stations report on identical timestamps, and callers hand rows straight
        // from SQL, where ties come back in whatever order the query plan chose — so the same rows
        // produced different observed curves run-to-run, alternating between two stable states and
        // blinking the graph's high/low labels on hours with no new data.
        // Android observation identity also includes the stored fetch site, so include its coordinates
        // to keep same-station/same-timestamp rows deterministic if a same-site boundary fragment
        // survives the read-path site collapse.
        // Sorting HERE keeps every caller deterministic — Android, desktop, and ActualsAggregator all
        // reach this function with their own queries. See ActualsRowOrderDeterminismTest.
        val filtered = observations
            .filter { !it.qcFailed && matchesObservationSource(it, displaySourceId) }
            .sortedWith(
                compareBy(
                    { it.timestamp },
                    { it.stationId },
                    { it.locationLat },
                    { it.locationLon },
                ),
            )

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

        val forecastSeries = HourlyForecastSelector
            .selectForecastsByTime(hourlyForecasts, displaySourceId, userLat, userLon)
            .values
            .sortedBy { it.dateTime }
        val byStation = filtered.groupBy { it.stationId }.mapValues { it.value.sortedBy { obs -> obs.timestamp } }
        val candidateTimes = filtered.map { it.timestamp }.distinct().sorted()
        val result = mutableListOf<ObservationReading>()
        val candidates = mutableListOf<DecayBlendInput>()
        // Parallel to [candidates] by index, populated only while capturing, so the hot render path
        // allocates nothing extra.
        val captureMeta =
            if (captureBreakdowns > 0 || captureLatestDominantAtOrBeforeMs != null) {
                mutableListOf<ContributionMeta>()
            } else {
                null
            }
        val breakdowns = if (captureBreakdowns > 0) ArrayDeque<BlendBreakdown>() else null
        var latestDominantContribution: DominantBlend? = null
        val timePattern = if (onBlendDebug != null) DateTimeFormatter.ofPattern("HH:mm") else null
        // Dominance is per LOCAL day of the raw readings, so the daily aggregate (day-only obs)
        // and the hourly graph (multi-day context window) compute the same dominant station and
        // suppress the same lone-station candidates — keeping the two views' extrema in agreement.
        val dominantByDay = if (byStation.size > 1) dominantStationByDay(filtered, zoneId) else emptyMap()
        var loneStationSkipped = 0
        // Diagnostics for the synthetic-backfill deprioritisation. Counted here (cheaply, per
        // candidate timestamp) and reported ONCE below rather than per-timestamp, which would emit
        // hundreds of lines per resolve.
        var syntheticDeprioritized = 0
        val syntheticStationIds = linkedSetOf<String>()

        // Blend once per distinct observation timestamp (no time-bucket thinning). Observation data is
        // naturally sparse (NWS ~15 min, other sources hourly), so this is cheap, and — crucially — it
        // makes the emitted series independent of the query window. That is what lets the daily bar
        // (ActualsAggregator) and the hourly graph (build()) derive the SAME max/min for a day instead
        // of two window-dependent 5-min-thinned approximations. See daily_vs_hourly_actual_extrema_mismatch.
        for (targetTs in candidateTimes) {
            candidates.clear()
            captureMeta?.clear()
            var hasObserved = false
            var hasInterpolated = false
            var bestAnchorTs = -1L
            var anchorStation: ObservationReading? = null
            var soleContributorId: String? = null

            for ((stationId, stationObs) in byStation) {
                val resolved = resolveStationValueAt(stationObs, targetTs, forecastSeries)
                if (resolved != null) {
                    val ageMs = maxOf(0L, targetTs - resolved.anchorTs)
                    val isPersonal = resolved.stationType == PERSONAL_STATION_TYPE
                    val isSynthetic = ObservationSourceMatcher.isSyntheticBackfillStation(stationId, displaySourceId)
                    if (isSynthetic) syntheticStationIds.add(stationId)
                    candidates.add(DecayBlendInput(resolved.distanceKm, resolved.temperature, ageMs, isPersonal, isSynthetic))
                    captureMeta?.add(
                        ContributionMeta(
                            stationId = stationId,
                            stationName = stationObs.first().stationName,
                            stationType = resolved.stationType,
                            distanceKm = resolved.distanceKm,
                            lastReadingMs = resolved.anchorTs,
                            rawTemp = resolved.rawTemperature,
                            resolvedTemp = resolved.temperature,
                            sourceKind = resolved.sourceKind,
                            ageMs = ageMs,
                        ),
                    )
                    soleContributorId = if (candidates.size == 1) stationId else null
                    if (resolved.anchorTs > bestAnchorTs) bestAnchorTs = resolved.anchorTs
                    if (anchorStation == null) anchorStation = stationObs.minByOrNull { it.distanceKm }
                    when (resolved.sourceKind) {
                        "observed" -> hasObserved = true
                        "interpolated" -> hasInterpolated = true
                    }
                }
            }

            if (candidates.isEmpty()) continue

            // A timestamp covered by exactly ONE station gets that station's raw value (IDW with a
            // single candidate is the identity), so a distant station's outlier can silently become
            // the series min/max — e.g. a hills PWS with 3 pre-dawn readings that no other station's
            // 3h interpolation reach covers set a daily low 5°F below every official station. Only
            // the day's dominant station (best coverage) may stand alone; sparse stragglers must be
            // corroborated. A single-station day is trivially dominant, so sources with one station
            // (OPEN_METEO_MAIN, SILURIAN_MAIN) and full-day solo coverage are unaffected.
            if (candidates.size == 1 && byStation.size > 1) {
                val sole = soleContributorId
                val dominant = dominantByDay[Instant.ofEpochMilli(targetTs).atZone(zoneId).toLocalDate()]
                if (sole != null && sole != dominant) {
                    loneStationSkipped++
                    if (onBlendDebug != null && timePattern != null) {
                        val loopTs = targetTs
                        onBlendDebug.invoke {
                            val timeStr = Instant.ofEpochMilli(loopTs).atZone(zoneId).format(timePattern)
                            "skip t=$timeStr lone_station=$sole non-dominant (dominant=$dominant)"
                        }
                    }
                    continue
                }
            }

            val anchor = anchorStation ?: continue
            if (candidates.any { it.isSynthetic } && candidates.any { !it.isSynthetic }) syntheticDeprioritized++
            val weighted = computeWeightedBlend(candidates, personalStationWeight) ?: continue
            val blendedTemp = weighted.temperature
            val bestSourceKind = when {
                hasObserved -> "observed"
                hasInterpolated -> "interpolated"
                else -> "forecast_extrapolated"
            }

            if (onBlendDebug != null && timePattern != null) {
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
                if (
                    captureLatestDominantAtOrBeforeMs != null &&
                    targetTs <= captureLatestDominantAtOrBeforeMs &&
                    captureMeta != null
                ) {
                    val dominantIndex = weighted.weights.indices.maxByOrNull { weighted.weights[it] }
                    val dominantWeight = dominantIndex?.let { weighted.weights[it] } ?: 0.0
                    val weightSum = weighted.weights.sum()
                    if (dominantIndex != null && dominantWeight > 0.0) {
                        val meta = captureMeta[dominantIndex]
                        latestDominantContribution =
                            DominantBlend(
                                targetMs = targetTs,
                                contribution =
                                    BlendContribution(
                                        stationId = meta.stationId,
                                        stationName = meta.stationName,
                                        stationType = meta.stationType,
                                        distanceKm = meta.distanceKm,
                                        lastReadingMs = meta.lastReadingMs,
                                        rawTemp = meta.rawTemp,
                                        resolvedTemp = meta.resolvedTemp,
                                        sourceKind = meta.sourceKind,
                                        ageMs = meta.ageMs,
                                        weight = dominantWeight,
                                        weightShare = if (weightSum > 0.0) dominantWeight / weightSum else 0.0,
                                    ),
                            )
                    }
                }
                if (breakdowns != null && captureMeta != null) {
                    val weightSum = weighted.weights.sum()
                    breakdowns.addLast(
                        BlendBreakdown(
                            targetMs = targetTs,
                            blendedTemp = blendedTemp,
                            sourceKind = bestSourceKind,
                            contributions = captureMeta.mapIndexed { index, meta ->
                                val weight = weighted.weights[index]
                                BlendContribution(
                                    stationId = meta.stationId,
                                    stationName = meta.stationName,
                                    stationType = meta.stationType,
                                    distanceKm = meta.distanceKm,
                                    lastReadingMs = meta.lastReadingMs,
                                    rawTemp = meta.rawTemp,
                                    resolvedTemp = meta.resolvedTemp,
                                    sourceKind = meta.sourceKind,
                                    ageMs = meta.ageMs,
                                    weight = weight,
                                    weightShare = if (weightSum > 0.0) weight / weightSum else 0.0,
                                )
                            },
                        ),
                    )
                    // Keep only the most recent N; candidateTimes ascends, so the front is the oldest.
                    while (breakdowns.size > captureBreakdowns) breakdowns.removeFirst()
                }
            }
        }

        // Fires only when a synthetic backfill row actually competed with real stations, so it stays
        // silent on the normal path. VERBOSE: console/logcat only, never persisted to app_logs — the
        // blend runs on every minute tick. Without this the deprioritisation is completely invisible,
        // which is what made the NWS_MAIN hijack take a DB dig to find.
        if (syntheticDeprioritized > 0) {
            Log.v(
                TAG,
                "blend: deprioritized synthetic backfill ${syntheticStationIds.joinToString(",")} " +
                    "at $syntheticDeprioritized/${candidateTimes.size} candidate times " +
                    "(real stations present; source=$displaySourceId stations=${byStation.size})",
            )
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
                loneStationSkippedCount = loneStationSkipped,
                syntheticDeprioritizedCount = syntheticDeprioritized,
            ),
            // Newest first — the tab leads with the point the user is looking at on the graph.
            breakdowns = breakdowns?.reversed().orEmpty(),
            latestDominantContribution = latestDominantContribution,
        )
    }

    /** Per-station reporting fields captured alongside [DecayBlendInput]; diagnostics only. */
    private class ContributionMeta(
        val stationId: String,
        val stationName: String,
        val stationType: String,
        val distanceKm: Float,
        val lastReadingMs: Long,
        val rawTemp: Float,
        val resolvedTemp: Float,
        val sourceKind: String,
        val ageMs: Long,
    )

    /**
     * The station allowed to stand alone at a candidate timestamp, per local calendar day: most
     * readings that day, ties broken by smaller minimum distance, then stationId for determinism.
     */
    private fun dominantStationByDay(filtered: List<ObservationReading>, zoneId: ZoneId): Map<LocalDate, String> =
        filtered
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
            .mapValues { (_, dayObs) ->
                dayObs.groupBy { it.stationId }.entries.maxWith(
                    compareBy(
                        { entry -> entry.value.size },
                        { entry -> -entry.value.minOf { obs -> obs.distanceKm } },
                        { entry -> entry.key },
                    ),
                ).key
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
        val isPersonal: Boolean = false,
        val isSynthetic: Boolean = false,
    )

    private data class ResolvedStationValue(
        val temperature: Float,
        val distanceKm: Float,
        val sourceKind: String,
        val anchorTs: Long,
        val stationType: String,
        /**
         * The station's measured value at [anchorTs], before any interpolation or forecast carry-forward.
         * Equals [temperature] only when [sourceKind] is `observed`; the difference is the fabricated
         * part of the contribution, which is what the Blend tab exists to expose.
         */
        val rawTemperature: Float,
    )

    /**
     * The blended value together with the per-candidate weights that produced it, aligned by index
     * with the input list (0.0 for candidates that were excluded or decayed out).
     *
     * The weights are returned rather than recomputed by callers so the Blend tab's "weight share"
     * column cannot drift from the arithmetic that actually moves the graph — a second, independent
     * implementation of this blend is precisely the defect class this diagnostic exists to surface.
     */
    private class WeightedBlend(val temperature: Float, val weights: DoubleArray)

    private fun computeWeightedBlend(candidates: List<DecayBlendInput>, personalStationWeight: Double): WeightedBlend? {
        val weights = DoubleArray(candidates.size)
        // A fully-discounted PWS (weight 0) contributes nothing, so drop it entirely. This also keeps it
        // from winning the near-zero-distance tiebreak below when an official station exists elsewhere.
        val eligible = candidates.indices.filter { personalStationWeight > 0.0 || !candidates[it].isPersonal }
        if (eligible.isEmpty()) return null

        // A synthetic historical-actuals backfill row (`<SOURCE>_MAIN`) is a slice of the source's
        // hourly FORECAST re-filed as observations at distanceKm=0 — not a measurement. Its zero
        // distance used to win the near-zero override below outright, so a single stale backfill
        // suppressed every real station: a 15:26 NWS->Open-Meteo fallback minted NWS_MAIN rows that
        // drove the desktop's current temperature for the next 3 hours (~5 F hot) while five nearby
        // stations reported normally, then vanished at BLEND_MAX_AGE_MS and snapped the display back.
        // Rank the backfill strictly below real readings, but keep it as a LAST resort so
        // forecast-only sources (Open-Meteo, Silurian, ...) — and NWS during a total station outage —
        // still render an actual line.
        val realCandidates = eligible.filter { !candidates[it].isSynthetic && timeDecayFactor(candidates[it].ageMs) > 0f }
        val ranked = if (realCandidates.isNotEmpty()) realCandidates else eligible

        val veryClose = ranked.filter {
            candidates[it].distanceKm <= NEAR_ZERO_KM && timeDecayFactor(candidates[it].ageMs) > 0f
        }
        if (veryClose.isNotEmpty()) {
            // A station essentially at the user's location wins outright (IDW would divide by ~0). Prefer
            // an official station here too, falling back to the nearest personal one if that's all there is.
            val pick = veryClose.filter { !candidates[it].isPersonal }.minByOrNull { candidates[it].distanceKm }
                ?: veryClose.minBy { candidates[it].distanceKm }
            // Sole contributor: the tab should read 100%, not an IDW share this branch never computed.
            weights[pick] = 1.0
            return WeightedBlend(candidates[pick].temperature, weights)
        }

        var wSum = 0.0
        var tSum = 0.0
        for (index in ranked) {
            val candidate = candidates[index]
            val decay = timeDecayFactor(candidate.ageMs)
            if (decay <= 0f) continue
            val typeWeight = if (candidate.isPersonal) personalStationWeight else 1.0
            val w = typeWeight * decay.toDouble() / (candidate.distanceKm.toDouble() * candidate.distanceKm.toDouble())
            weights[index] = w
            tSum += w * candidate.temperature
            wSum += w
        }
        return if (wSum > 0.0) WeightedBlend((tSum / wSum).toFloat(), weights) else null
    }

    private fun resolveStationValueAt(
        stationObs: List<ObservationReading>,
        targetTs: Long,
        forecastSeries: List<HourlyForecast>,
    ): ResolvedStationValue? {
        if (stationObs.isEmpty()) return null
        // stationType is a property of the station (constant across its readings), so capture it once.
        val stationType = stationObs.first().stationType
        // Weight by the station's distance AT THIS targetTs, not stationObs.first().distanceKm. The
        // configured location can drift over time, so a station's logged distanceKm varies between
        // readings; using the earliest reading's distance made the IDW weight — and thus the blended
        // value at every timestamp — depend on the query window. That desynced the daily bar (today-only
        // window) from the hourly graph (72h context window) for the same day. See per-reading distance
        // below. (daily_vs_hourly_actual_extrema_mismatch)
        val insertIdx = stationObs.binarySearch { it.timestamp.compareTo(targetTs) }.let {
            if (it >= 0) {
                val hit = stationObs[it]
                return ResolvedStationValue(
                    hit.temperature,
                    hit.distanceKm,
                    "observed",
                    hit.timestamp,
                    stationType,
                    hit.temperature,
                )
            }
            -(it + 1)
        }

        val before = if (insertIdx > 0) stationObs[insertIdx - 1] else null
        val after = if (insertIdx < stationObs.size) stationObs[insertIdx] else null

        return when {
            before != null && after != null -> {
                val gapMs = after.timestamp - before.timestamp
                if (gapMs > MAX_INTERPOLATION_GAP_MS) {
                    // Too far apart to interpolate across, but [targetTs] may still be within forward
                    // extrapolation reach of [before] — so extrapolate rather than dropping the station.
                    // Bailing here made a station's contribution at [targetTs] depend on whether some
                    // *later* reading happened to exist: a station whose last reading was 20:47 covered
                    // 20:50..23:47 by extrapolation, then silently vanished from those same past hours
                    // the moment its next reading landed hours later (20:47 -> next > 3h fails the gap
                    // check above). The blend then renormalised over the remaining stations and the whole
                    // observed curve moved by up to ~1.2 F, flipping flat ties into extrema and making
                    // high/low labels blink between renders with no new data for those hours.
                    // Resolution must depend only on readings NEAR the target, never on a distant future one.
                    return extrapolateForward(before, targetTs, forecastSeries, stationType)
                }
                val fraction = (targetTs - before.timestamp).toFloat() / gapMs.toFloat()
                val interpolated = before.temperature + (after.temperature - before.temperature) * fraction
                val interpolatedDistanceKm = before.distanceKm + (after.distanceKm - before.distanceKm) * fraction
                ResolvedStationValue(
                    interpolated,
                    interpolatedDistanceKm,
                    "interpolated",
                    after.timestamp,
                    stationType,
                    after.temperature,
                )
            }
            before != null -> extrapolateForward(before, targetTs, forecastSeries, stationType)
            else -> null
        }
    }

    /**
     * [before]'s reading carried forward to [targetTs] by the forecast's change over that span, or null
     * when [targetTs] is beyond [MAX_EXTRAPOLATION_GAP_MS] of it. Reached both when the station has no
     * later reading at all and when its next reading is too far past [targetTs] to interpolate across,
     * so the two cases resolve identically.
     */
    private fun extrapolateForward(
        before: ObservationReading,
        targetTs: Long,
        forecastSeries: List<HourlyForecast>,
        stationType: String,
    ): ResolvedStationValue? {
        val gapMs = targetTs - before.timestamp
        if (gapMs > MAX_EXTRAPOLATION_GAP_MS) return null
        val baseForecast = forecastTemperatureAt(forecastSeries, before.timestamp) ?: return null
        val targetForecast = forecastTemperatureAt(forecastSeries, targetTs) ?: return null
        val extrapolated = before.temperature + (targetForecast - baseForecast)
        return ResolvedStationValue(
            extrapolated,
            before.distanceKm,
            "forecast_extrapolated",
            before.timestamp,
            stationType,
            before.temperature,
        )
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
