package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Aggregates raw observations into daily highs and lows, grouped by source.
 * Uses time-aligned IDW blending to match what the live widget displayed during the day.
 */
object ActualsAggregator {

    private const val DAY_START_HOUR = 8
    private const val DAY_END_HOUR = 20

    // Daily extremes blend the temperature series over a window that extends this far past each
    // side of the calendar day, so stations whose feed lapsed near midnight still participate in
    // the day's edge timestamps (interpolation reach is only ~3h). Extrema are still taken from
    // the target day alone. This keeps the daily low/high in step with the hourly graph, which
    // blends over its multi-day render window; a day-isolated window instead dropped such stations
    // and let a lone cold outlier dominate. See daily_vs_hourly_actual_extrema_mismatch.
    //
    // Public and authoritative: every Android caller that fetches observations to feed aggregate()
    // (daily_history recompute AND the live-today display paths) must reach back at least this far
    // across midnight, or the widen is defeated by a too-narrow query. One constant = no drift.
    const val DAILY_BLEND_CONTEXT_MS = 24 * 3600_000L

    data class DailyPrecip(val total: Float?, val day: Float?, val night: Float?)

    data class CurrentObservationResolution(
        val temperature: Float,
        val observedAt: Long,
        val rowFetchedAt: Long,
        val dominantContribution: DominantBlendContribution?,
    )

    /**
     * Resolves the current observed temperature by blending the latest station observations.
     */
    fun resolveCurrentObservation(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long = System.currentTimeMillis(),
        lookbackHours: Long = 12L,
        lookaheadHours: Long = 3L,
        zoneId: ZoneId = ZoneId.systemDefault(),
        personalStationWeight: Double = 1.0
    ): Triple<Float, Long, Long>? =
        resolveCurrentObservationInternal(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            nowMs = nowMs,
            lookbackHours = lookbackHours,
            lookaheadHours = lookaheadHours,
            zoneId = zoneId,
            personalStationWeight = personalStationWeight,
            includeDominantContribution = false,
        )?.let { Triple(it.temperature, it.observedAt, it.rowFetchedAt) }

    /**
     * Resolves the same current blend as [resolveCurrentObservation] and includes the station whose
     * final blend weight is greatest at that exact point.
     */
    fun resolveCurrentObservationDetails(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long = System.currentTimeMillis(),
        lookbackHours: Long = 12L,
        lookaheadHours: Long = 3L,
        zoneId: ZoneId = ZoneId.systemDefault(),
        personalStationWeight: Double = 1.0,
    ): CurrentObservationResolution? =
        resolveCurrentObservationInternal(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            nowMs = nowMs,
            lookbackHours = lookbackHours,
            lookaheadHours = lookaheadHours,
            zoneId = zoneId,
            personalStationWeight = personalStationWeight,
            includeDominantContribution = true,
        )

    private fun resolveCurrentObservationInternal(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long,
        lookbackHours: Long,
        lookaheadHours: Long,
        zoneId: ZoneId,
        personalStationWeight: Double,
        includeDominantContribution: Boolean,
    ): CurrentObservationResolution? {
        val truncatedMs = (nowMs / 3600_000L) * 3600_000L
        val minute = (nowMs % 3600_000L) / 60_000L
        val alignedCenterMs = if (minute >= 30) truncatedMs + 3600_000L else truncatedMs

        val contextStartMs = alignedCenterMs - (lookbackHours * 3600_000L)
        val contextEndMs = alignedCenterMs + (lookaheadHours * 3600_000L)

        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            startMs = contextStartMs,
            endMs = contextEndMs,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
            onBlendDebug = null,
            captureLatestDominantAtOrBeforeMs = nowMs.takeIf { includeDominantContribution },
        )

        val pastBlended = result.observations.filter { it.timestamp <= nowMs }

        val latestObs = pastBlended
            .filter { it.condition == "observed" }
            .maxByOrNull { it.timestamp }
            ?: pastBlended
                .filter { it.condition == "interpolated" }
                .maxByOrNull { it.timestamp }


        return latestObs?.let {
            CurrentObservationResolution(
                temperature = it.temperature,
                observedAt = it.timestamp,
                rowFetchedAt = it.fetchedAt,
                dominantContribution =
                    result.latestDominantContribution?.takeIf { dominant ->
                        dominant.targetMs == it.timestamp
                    },
            )
        }
    }

    /**
     * Aggregates raw observations into daily highs and lows.
     */
    fun aggregate(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        locationLat: Double,
        locationLon: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
        updatedAtMs: Long = System.currentTimeMillis(),
        personalStationWeight: Double = 1.0
    ): List<DailyHistory> {
        val today = LocalDate.now(zoneId)

        return observations
            .filter { it.stationId != "NWS_BLEND" }
            .groupBy { it.api }
            .flatMap { (sourceId, obsList) ->
                val sourceHourly = hourlyForecasts.filter { it.source == sourceId || it.source == "GENERIC_GAP" }
                obsList
                    .groupBy { obs -> Instant.ofEpochMilli(obs.timestamp).atZone(zoneId).toLocalDate() }
                    .mapNotNull { (date, dayObs) ->
                        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val dayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

                        // Feed the blend a ±context window drawn from the source's full observation
                        // list (not just this day's rows) so the contributing station set matches the
                        // hourly graph. Extrema are still extracted from the target day only.
                        val windowStartMs = dayStartMs - DAILY_BLEND_CONTEXT_MS
                        val windowEndMs = dayEndMs + DAILY_BLEND_CONTEXT_MS
                        val windowObs = obsList.filter { it.timestamp in windowStartMs until windowEndMs }

                        val (computedHighTemp, computedLowTemp) = blendDailyExtremesViaSeries(
                            contextObs = windowObs,
                            hourlyForecasts = sourceHourly,
                            sourceId = sourceId,
                            locationLat = locationLat,
                            locationLon = locationLon,
                            dayStartMs = dayStartMs,
                            dayEndMs = dayEndMs,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                            personalStationWeight = personalStationWeight,
                            zoneId = zoneId,
                        ) ?: return@mapNotNull null

                        val mostCommonCondition = dayObs
                            .map { it.condition }
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key ?: "Unknown"

                        val precip = resolveDailyPrecip(
                            dayObs, sourceHourly, date, zoneId,
                            allowForecastFallback = !date.isBefore(today),
                        )

                        DailyHistory(
                            date = date.toEpochDay() * 86_400_000L, // UTC midnight epoch millis approximation
                            source = sourceId,
                            locationLat = locationLat,
                            locationLon = locationLon,
                            computedHighTemp = computedHighTemp,
                            computedLowTemp = computedLowTemp,
                            condition = mostCommonCondition,
                            updatedAt = updatedAtMs,
                            precipAmountMm = precip.total,
                            precipDayMm = precip.day,
                            precipNightMm = precip.night,
                        )
                    }
            }
    }

    private fun blendDailyExtremesViaSeries(
        contextObs: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        sourceId: String,
        locationLat: Double,
        locationLon: Double,
        dayStartMs: Long,
        dayEndMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        personalStationWeight: Double = 1.0,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<Float, Float>? {
        if (contextObs.isEmpty()) return null

        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = contextObs,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = sourceId,
            userLat = locationLat,
            userLon = locationLon,
            startMs = windowStartMs,
            endMs = windowEndMs,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
            onBlendDebug = null,
        )

        // Extrema belong to the target calendar day; the wider blend window only stabilises the
        // station set at the day's edges, it must not leak neighbouring days into the high/low.
        val series = result.observations.filter { it.timestamp in dayStartMs until dayEndMs }
        if (series.isEmpty()) return null
        val high = series.maxOf { it.temperature }
        val low = series.minOf { it.temperature }
        return high to low
    }

    fun resolveDailyPrecip(
        dayObs: List<ObservationReading>,
        sourceHourly: List<HourlyForecast>,
        date: LocalDate,
        zone: ZoneId,
        allowForecastFallback: Boolean = true,
    ): DailyPrecip {
        if (dayObs.any { it.precipAmountMm != null }) {
            return DailyPrecip(
                total = dayObs.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum(),
                day = sumDaytimePrecip(dayObs, date, zone),
                night = sumNighttimePrecip(dayObs, date, zone),
            )
        }
        
        if (!allowForecastFallback) {
            return DailyPrecip(total = null, day = null, night = null)
        }

        // Forecast fallback
        val hourlyForDate = sourceHourly
            .filter { Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() == date }
        
        val total = hourlyForDate.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
        val day = hourlyForDate.filter { 
            Instant.ofEpochMilli(it.dateTime).atZone(zone).hour in DAY_START_HOUR until DAY_END_HOUR 
        }.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
        val night = hourlyForDate.filter { 
            val hour = Instant.ofEpochMilli(it.dateTime).atZone(zone).hour
            hour < DAY_START_HOUR || hour >= DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()

        return DailyPrecip(total = total, day = day, night = night)
    }

    private fun sumDaytimePrecip(obs: List<ObservationReading>, date: LocalDate, zone: ZoneId): Float? {
        val daytime = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour in DAY_START_HOUR until DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }
        return if (daytime.isEmpty()) null else daytime.sum()
    }

    private fun sumNighttimePrecip(obs: List<ObservationReading>, date: LocalDate, zone: ZoneId): Float? {
        val night1 = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour < DAY_START_HOUR
        }.mapNotNull { it.precipAmountMm }

        val night2 = obs.filter {
            val dt = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            dt.toLocalDate() == date && dt.hour >= DAY_END_HOUR
        }.mapNotNull { it.precipAmountMm }

        val all = night1 + night2
        return if (all.isEmpty()) null else all.sum()
    }
}
