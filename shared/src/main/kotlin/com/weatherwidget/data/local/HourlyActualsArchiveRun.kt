package com.weatherwidget.data.local

import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.shared.actuals.ObservedPrecip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Archives what each source drew as observed into `hourly_actuals`, one local day at a time, for the
 * days wholly past [RetentionPolicy.HOURLY_ACTUALS_ARCHIVE_DAYS]. Raw `observations` are then free to
 * go, so hourly views and rain accuracy reach further back than station data is kept.
 *
 * Every value comes from the code the views themselves call, so an archived hour is the hour they
 * drew: temperature is [ActualTemperatureSeriesBuilder.build]'s top-of-hour mark, rain is
 * [ObservedPrecip.byHour], cloud is [MetarCloudBlender.fromSiteRows]'s report nearest the hour. Keyed
 * by display source because the three resolve a source's feed differently (borrowing, and rain's own
 * set of rows).
 *
 * Rows are frozen: inserts ignore conflicts, so a pass re-reading the newest archived day only adds
 * what is missing, and a later blend or weight change never rewrites history (as `daily_history`).
 * See plans/261101-keep-observations-trim-hourly-forecasts.md.
 */
object HourlyActualsArchiveRun {
    private const val HOUR_MS = 3_600_000L
    private const val CONTEXT_MS = 24L * HOUR_MS
    private const val CONTEXT_HOURS = 24L

    /** Sources a view can display. METAR and Synoptic are feeds read through them; the gap filler observes nothing. */
    private val SOURCES = WeatherSource.entries - setOf(WeatherSource.METAR, WeatherSource.SYNOPTIC, WeatherSource.GENERIC_GAP)

    interface Host {
        /** The newest archived hour; null on an empty table. */
        suspend fun latestArchivedHour(): Long?

        suspend fun oldestObservation(): Long?

        /** Exact stored (lat, lon) of the observations in [startMs, endMs), with their row counts. */
        suspend fun observationSites(startMs: Long, endMs: Long): Map<Pair<Double, Double>, Int>

        /** The same read the views use for this site, all APIs: [startMs, endMs). */
        suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading>

        suspend fun hourlyForecasts(startMs: Long, endMs: Long, lat: Double, lon: Double): List<HourlyForecast>

        fun personalStationWeight(): Double

        /** Ignores rows whose key already exists. */
        suspend fun insertHourlyActuals(rows: List<HourlyActual>)
    }

    /** [throughMs]: every hour before this has had its pass; raw rows behind it may go. */
    data class Result(val days: Int, val rows: Int, val throughMs: Long)

    suspend fun run(host: Host, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val archiveAgeMs = RetentionPolicy.HOURLY_ACTUALS_ARCHIVE_DAYS * 24L * HOUR_MS
        val endDay = Instant.ofEpochMilli(nowMs - archiveAgeMs).atZone(zone).toLocalDate()
        val throughMs = endDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val oldest = host.oldestObservation() ?: return Result(0, 0, throughMs)
        var day = Instant.ofEpochMilli(maxOf(oldest, host.latestArchivedHour() ?: oldest)).atZone(zone).toLocalDate()
        var days = 0
        var rows = 0
        while (day.isBefore(endDay)) {
            val archived = archiveDay(host, day, nowMs, zone)
            if (archived.isNotEmpty()) host.insertHourlyActuals(archived)
            rows += archived.size
            days++
            day = day.plusDays(1)
        }
        return Result(days, rows, throughMs)
    }

    private suspend fun archiveDay(host: Host, day: LocalDate, nowMs: Long, zone: ZoneId): List<HourlyActual> {
        val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = host.observationSites(startMs, endMs)
        val sites = HistorySnapshotRetention.clusterCenters(counts, LocationMatch.TOLERANCE_DEG).values.toSet()
        return sites.flatMap { (lat, lon) ->
            val observations = host.observations(startMs - CONTEXT_MS, endMs + CONTEXT_MS, lat, lon)
            val forecasts = host.hourlyForecasts(startMs - CONTEXT_MS, endMs + CONTEXT_MS, lat, lon)
            SOURCES.flatMap { source ->
                val temps = temperatures(forecasts, observations, source, lat, lon, day, host.personalStationWeight(), nowMs, zone)
                val rain = ObservedPrecip.byHour(ObservedPrecip.rowsFor(observations, source), zone)
                val cloud = MetarCloudBlender.fromSiteRows(startMs, endMs, source.id) { readStart, readEnd ->
                    observations.filter { it.timestamp in readStart until readEnd }
                }
                val siteLat = LocationMatch.quantize(lat)
                val siteLon = LocationMatch.quantize(lon)
                hourMarks(day, zone).mapNotNull { (local, hourMs) ->
                    val cloudKey = nearestReport(cloud.hours.keys, hourMs)
                    val bands = cloudKey?.let { cloud.bands[it] }
                    HourlyActual(
                        hourMs = hourMs,
                        source = source.id,
                        locationLat = siteLat,
                        locationLon = siteLon,
                        temperature = temps[hourMs],
                        precipAmountMm = rain[local],
                        cloudCover = cloudKey?.let { cloud.hours[it] },
                        cloudCoverLow = bands?.low,
                        cloudCoverMid = bands?.mid,
                        cloudCoverHigh = bands?.high,
                        archivedAt = nowMs,
                    ).takeIf { it.temperature != null || it.precipAmountMm != null || it.cloudCover != null }
                }
            }
        }
    }

    private fun temperatures(
        forecasts: List<HourlyForecast>,
        observations: List<ObservationReading>,
        source: WeatherSource,
        lat: Double,
        lon: Double,
        day: LocalDate,
        personalStationWeight: Double,
        nowMs: Long,
        zone: ZoneId,
    ): Map<Long, Float> {
        // No supportsTemperatureActuals gate: a borrowing source draws its provider's line, and build()
        // already resolves that, exactly as for the graph.
        val lastHoursBack = 23L
        return ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = day.atTime(23, 0),
            displaySourceId = source.id,
            userLat = lat,
            userLon = lon,
            backHours = lastHoursBack,
            forwardHours = 0,
            contextLookbackHours = lastHoursBack + CONTEXT_HOURS,
            contextLookaheadHours = CONTEXT_HOURS,
            now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone),
            zoneId = zone,
            personalStationWeight = personalStationWeight,
        ).points
            .filter { it.isActual && it.actualTemp != null }
            .associate { it.timeMs to it.actualTemp!! }
    }

    private fun hourMarks(day: LocalDate, zone: ZoneId): List<Pair<LocalDateTime, Long>> =
        (0..23).map { hour -> day.atTime(hour, 0).let { it to it.atZone(zone).toInstant().toEpochMilli() } }

    /** Cloud keys are native report times for a station blend, hour marks for a provider series. */
    private fun nearestReport(keys: Set<Long>, hourMs: Long): Long? =
        keys.filter { abs(it - hourMs) < HOUR_MS / 2 }.minByOrNull { abs(it - hourMs) }
}
