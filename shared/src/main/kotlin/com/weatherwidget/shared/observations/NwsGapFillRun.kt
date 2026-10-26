package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.pastDayLacksAfternoonCoverage
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "NwsGapFillRun"

/**
 * The NWS observation gap-fill, one rule for both platforms: which recent days need station history,
 * how far back to ask, which stations in what order, when to stop, and what to log. Android's
 * `NwsObservationBackfiller` and desktop's `refreshObservationWindow` each supply a [Host] for the
 * station list, the per-station fetch, storage and the extremes recompute
 * (plans/261025-shared-nws-gap-fill.md). Desktop pulled 7 days on every wake whether or not a day
 * was missing; it now follows Android's need check and 3-day window.
 */
object NwsGapFillRun {
    /** How far back a fill asks each station: the hourly graph's historical lookback. */
    const val BACKFILL_DAYS = 3

    const val MAX_STATIONS = NwsStationBundles.MAX_STATIONS

    /** Today joins the need only once its early hours have had time to report. */
    private const val TODAY_FROM_HOUR = 2

    private const val MS_IN_A_DAY = 86_400_000L

    interface Host {
        /** The site's NWS observation stations, nearest first; [FetchOutcome.Failed] = never answered. */
        suspend fun stations(latitude: Double, longitude: Double): FetchOutcome<List<NwsApi.StationInfo>>

        /** One station's [startIso, endIso) window through [NwsStationHistoryFetcher], with the platform's web leg. */
        suspend fun fetchStation(
            station: NwsApi.StationInfo,
            index: Int,
            latitude: Double,
            longitude: Double,
            startIso: String,
            endIso: String,
            fallbackLogTag: String,
        ): StationRows

        suspend fun store(rows: List<ObservationReading>, latitude: Double, longitude: Double)

        /** daily_history rows in the box for day keys in [startDayMs, endDayMs] (UTC-midnight keys). */
        suspend fun dailyHistoryRows(startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double): List<DayRow>

        /** Stored observations in the box in [startMs, endMs); a host may pre-filter to NWS rows. */
        suspend fun observations(startMs: Long, endMs: Long, latitude: Double, longitude: Double): List<ObsStamp>

        /** Recompute [days]' extremes from what is now stored, bypassing any unchanged-input skip. */
        suspend fun recomputeExtremes(latitude: Double, longitude: Double, days: Set<LocalDate>)

        suspend fun log(tag: String, message: String, level: String)
    }

    data class StationRows(
        val rows: List<ObservationReading>,
        val usedWebFallback: Boolean,
        /** Simple name of the exception the API call threw, or null when it answered. */
        val apiFailure: String? = null,
    )

    data class DayRow(val dateMs: Long, val source: String)

    data class ObsStamp(val timestamp: Long, val api: String, val stationId: String)

    data class Result(
        /** Days the need check asked for; empty = nothing to do, and nothing was fetched. */
        val needed: Set<LocalDate>,
        /** Needed days still missing or incomplete after the fill. */
        val remaining: Set<LocalDate>,
        val stationsTried: Int,
        val rows: Int,
        /**
         * NWS never answered: the station lookup failed, or every tried station's fetch failed.
         * Distinct from an answered-but-empty fill, which is a real data limit.
         */
        val unreachable: Boolean,
    )

    /**
     * The daily pass: fills the last [BACKFILL_DAYS] when day−2, yesterday or (from 02:00) today has no
     * NWS daily_history row, or a past one was reduced from an overnight-only slice. Stations are
     * tried nearest first and the pass stops as soon as every needed day is complete.
     */
    suspend fun daily(host: Host, latitude: Double, longitude: Double, nowMs: Long, zone: ZoneId): Result {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        val rangeStart = today.minusDays(2).dayKey()
        val rangeEnd = today.dayKey()
        val required = buildSet {
            add(rangeStart)
            add(today.minusDays(1).dayKey())
            if (now.hour >= TODAY_FROM_HOUR) add(rangeEnd)
        }
        val existing = nwsDayKeys(host, rangeStart, rangeEnd, latitude, longitude)
        val incomplete = incompletePastDays(host, existing, latitude, longitude, zone, today)
        val missing = required - existing
        Log.d(
            TAG,
            "History check required=${required.toDates()} existing=${existing.toDates()} " +
                "missing=${missing.toDates()} incomplete=${incomplete.toDates()} hour=${now.hour}",
        )
        val needed = missing + incomplete
        if (needed.isEmpty()) return Result(emptySet(), emptySet(), 0, 0, unreachable = false)

        val stationsOutcome = host.stations(latitude, longitude)
        val stations = stationsOutcome.valueOrNull().orEmpty().take(MAX_STATIONS)
        if (stations.isEmpty()) {
            val unreachable = stationsOutcome is FetchOutcome.Failed
            host.log(
                "NWS_DAILY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=${if (unreachable) "stations_unreachable" else "no_stations"}",
                "WARN",
            )
            return Result(needed.toDates().toSet(), needed.toDates().toSet(), 0, 0, unreachable)
        }

        val startIso = DateTimeFormatter.ISO_INSTANT.format(now.minusDays(BACKFILL_DAYS.toLong()).toInstant())
        val endIso = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        val remaining = needed.toMutableSet()
        var tried = 0
        var failed = 0
        var rows = 0
        for ((index, station) in stations.withIndex()) {
            tried++
            val fetch = fetchAndStore(
                host, station, index, latitude, longitude, startIso, endIso,
                fallbackLogTag = "NWS_DAILY_WEB_FALLBACK",
                stationLogTag = "NWS_DAILY_BACKFILL_STATION",
            )
            if (fetch.failed) failed++
            if (fetch.rows.isEmpty()) continue
            rows += fetch.rows.size

            val days = fetch.rows.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.toSet()
            host.recomputeExtremes(latitude, longitude, days)
            val rowDates = nwsDayKeys(host, rangeStart, rangeEnd, latitude, longitude)
            val stillIncomplete = incompletePastDays(host, rowDates, latitude, longitude, zone, today)
            remaining.removeAll(rowDates - stillIncomplete)
            if (remaining.isEmpty()) break
        }

        if (remaining.isNotEmpty()) {
            host.log(
                "NWS_DAILY_BACKFILL_INCOMPLETE",
                "lat=$latitude lon=$longitude remaining=${remaining.toDates()}",
                "WARN",
            )
            Log.w(TAG, "Daily backfill still missing/incomplete for ${remaining.toDates()}")
        }
        return Result(
            needed = needed.toDates().toSet(),
            remaining = remaining.toDates().toSet(),
            stationsTried = tried,
            rows = rows,
            unreachable = failed == tried,
        )
    }

    private class StationFetch(val rows: List<ObservationReading>, val failed: Boolean)

    private suspend fun fetchAndStore(
        host: Host,
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        startIso: String,
        endIso: String,
        fallbackLogTag: String,
        stationLogTag: String,
    ): StationFetch =
        try {
            val result = host.fetchStation(station, index, latitude, longitude, startIso, endIso, fallbackLogTag)
            // A station past the web-fallback cap gets no web rows, so a timeout here lands as a bare
            // `rows=0` that reads exactly like a station with nothing to report (KSJC, 2026-08-21).
            // The failure marker is the only thing separating the two on this line.
            host.log(
                stationLogTag,
                "station=${station.id} rows=${result.rows.size} web=${result.usedWebFallback}" +
                    (result.apiFailure?.let { " apiFetchFailed=$it" } ?: ""),
                "INFO",
            )
            if (result.rows.isNotEmpty()) host.store(result.rows, latitude, longitude)
            StationFetch(result.rows, failed = result.rows.isEmpty() && result.apiFailure != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("${stationLogTag}_FAIL", "station=${station.id} error=${e::class.simpleName}:${e.message}", "WARN")
            Log.e(TAG, "Observation backfill failed for ${station.id}", e)
            StationFetch(emptyList(), failed = true)
        }

    private suspend fun nwsDayKeys(host: Host, startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double): Set<Long> =
        host.dailyHistoryRows(startDayMs, endDayMs, latitude, longitude)
            .filter { it.source == WeatherSource.NWS.id }
            .mapTo(mutableSetOf()) { it.dateMs }

    /** Past [dayKeys] whose own NWS station rows (the blend's synthetic row excluded) stop before the afternoon. */
    private suspend fun incompletePastDays(
        host: Host,
        dayKeys: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
    ): Set<Long> =
        dayKeys.filterTo(mutableSetOf()) { key ->
            val date = LocalDate.ofEpochDay(key / MS_IN_A_DAY)
            if (!date.isBefore(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val timestamps = host.observations(dayStart, dayEnd, latitude, longitude)
                .filter { it.api == WeatherSource.NWS.id && it.stationId != NwsBlend.STATION_ID }
                .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    private fun LocalDate.dayKey(): Long = toEpochDay() * MS_IN_A_DAY

    private fun Set<Long>.toDates(): List<LocalDate> = map { LocalDate.ofEpochDay(it / MS_IN_A_DAY) }.sorted()
}
