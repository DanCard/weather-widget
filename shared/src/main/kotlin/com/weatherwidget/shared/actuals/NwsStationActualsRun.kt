package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * NWS daily actuals from the dedicated `/stations/{id}/observations` pull, one run for both
 * platforms: which dates are missing, how many stations, the stored-observation fallback, the rows
 * written and every `NWS_STATION_ACTUALS*` log. Was Android's `NwsApiDailyActualsFetcher` +
 * `NwsStationActualsStore` and desktop's `fillNwsStationActualsIfNeeded`, around the shared
 * [NwsStationActualsMaintenance] planner (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 * Idempotent: a date drops out once filled.
 */
object NwsStationActualsRun {
    /** Matches the station depth the observation backfill uses. */
    const val MAX_STATIONS = 5

    private const val DAY_MS = 86_400_000L

    interface Host {
        val lat: Double
        val lon: Double
        val personalStationWeight: Double

        /** NWS-and-other `daily_history` rows at the site in [startMs, endMs] (UTC-midnight keys). */
        suspend fun dailyHistory(startMs: Long, endMs: Long): List<DailyHistory>

        /** Observation stations nearest first; empty when NWS can't say. */
        suspend fun stations(): List<NwsApi.StationInfo>

        /** NWS hourly for one day at the site, for the blend. */
        suspend fun nwsHourly(dayStartMs: Long, dayEndMs: Long): List<HourlyForecast>

        /** One station, one day; throws on a failed request, an empty list when it answered with nothing. */
        suspend fun stationDay(station: NwsApi.StationInfo, startIso: String, endIso: String): List<ObservationReading>

        /** Our retained observations at the site in [startMs, endMs). */
        suspend fun storedObservations(startMs: Long, endMs: Long): List<ObservationReading>

        suspend fun upsert(rows: List<DailyHistory>)

        suspend fun log(tag: String, message: String, level: String)
    }

    /**
     * NWS dates still missing a station-derived actual — including rows whose values are fine but
     * whose provenance is missing (a value-only predicate could never repair the Pixel's 2026-08-06).
     */
    fun missingDates(rows: List<DailyHistory>): List<Long> =
        rows.filter {
            it.source == WeatherSource.NWS.id &&
                (it.apiHighTemp == null || it.apiLowTemp == null || it.actualsSource == null)
        }.map { it.date }.distinct()

    suspend fun run(host: Host, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        // The range and the planner judge "today" from the same clock.
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val startMs = today.minusDays(NwsDailyExtremesFetch.MAX_LOOKBACK_DAYS).toEpochDay() * DAY_MS
        val endMs = today.toEpochDay() * DAY_MS
        val missing = missingDates(host.dailyHistory(startMs, endMs))
        if (missing.isEmpty()) return

        // Personal stations included: the blend interpolates across all of them, discounted by the
        // user's weight; only the api actual is official-only (StationDailyExtremes enforces it).
        val stations = host.stations().take(MAX_STATIONS)
        if (stations.isEmpty()) {
            // Never a silent return: an invisible skip reads as "nothing to do".
            host.log("NWS_STATION_ACTUALS_FAIL", "dates=${missing.size} reason=no_stations", "WARN")
            return
        }
        val stationsById = stations.associateBy { it.id }

        val outcome = NwsStationActualsMaintenance.resolve(
            missingDates = missing,
            stationIdsNearestFirst = stations.map { it.id },
            userLat = host.lat,
            userLon = host.lon,
            personalStationWeight = host.personalStationWeight,
            zone = zone,
            nowMs = nowMs,
            hourlyForecastsForDay = { dayStartMs, dayEndMs -> host.nwsHourly(dayStartMs, dayEndMs) },
            // null == the request failed (Unavailable, retried); empty == answered with nothing
            // (Insufficient, falls back to stored observations). Never collapse the two.
            fetchStationDay = { stationId, startIso, endIso ->
                stationsById[stationId]?.let { station ->
                    try {
                        host.stationDay(station, startIso, endIso)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        host.log("NWS_STATION_ACTUALS_FAIL", "station=${station.id} error=${e::class.simpleName}:${e.message}", "WARN")
                        null
                    }
                }
            },
            stationExtremeFromStoredObservations = { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                StationDailyExtremes.resolve(
                    observations = host.storedObservations(dayStart, dayEnd),
                    sourceId = WeatherSource.NWS.id,
                    dayStartMs = dayStart,
                    dayEndMs = dayEnd,
                    zone = zone,
                )
            },
        )

        val pulled = outcome.pulled
        val cached = outcome.cached
        if (pulled.isNotEmpty() || cached.isNotEmpty()) {
            val touched = pulled.keys + cached.keys
            val rows = host.dailyHistory(touched.min(), touched.max()).filter { it.source == WeatherSource.NWS.id }
            // Every same-date NWS fragment in the box: the value describes a station, not a coordinate.
            val pulledRows = rows.mapNotNull { row -> pulled[row.date]?.let { applyPulled(row, it, nowMs) } }
            val cachedRows = rows.mapNotNull { row -> cached[row.date]?.let { applyCached(row, it, nowMs) } }
            if (pulledRows.isNotEmpty() || cachedRows.isNotEmpty()) host.upsert(pulledRows + cachedRows)
            if (pulledRows.isNotEmpty()) {
                host.log(
                    "NWS_STATION_ACTUALS",
                    "rows=${pulledRows.size} dates=${pulled.size} " +
                        pulled.entries.sortedBy { it.key }.joinToString(" ") { (date, a) ->
                            "${LocalDate.ofEpochDay(date / DAY_MS)}=blend[${a.blendHigh}/${a.blendLow}]" +
                                (a.station?.let { "${it.stationId}[${it.high}/${it.low} n=${it.readingCount}]" } ?: "no-station")
                        },
                    "DEBUG",
                )
            }
            if (cachedRows.isNotEmpty()) {
                host.log(
                    "NWS_STATION_ACTUALS_CACHED",
                    "rows=${cachedRows.size} dates=${cached.size} " +
                        cached.entries.sortedBy { it.key }.joinToString(" ") { (date, s) ->
                            "${LocalDate.ofEpochDay(date / DAY_MS)}=${s.stationId}[hi=${s.high} lo=${s.low} n=${s.readingCount}]"
                        },
                    "DEBUG",
                )
            }
        }
        host.log(
            "NWS_STATION_ACTUALS_OUTCOME",
            "requested=${missing.size} stations=${stations.size} pulled=${pulled.size} cached=${cached.size} " +
                "insufficientUnresolved=${outcome.insufficientUnresolved} unavailable=${outcome.unavailable}",
            "DEBUG",
        )
    }

    /**
     * A pulled day: the blend (only ever overwritten by a pull that produced one) and the station
     * extreme when one qualified — a day can have a blend but no station extreme (personal stations
     * feed one and are barred from the other), so a stored api value is kept then. Null when unchanged.
     */
    fun applyPulled(row: DailyHistory, a: NwsDailyExtremesFetch.DailyActualsFromStations, nowMs: Long): DailyHistory? =
        row.copy(
            computedHighTemp = a.blendHigh,
            computedLowTemp = a.blendLow,
            computedHighAt = a.blendHighAt,
            computedLowAt = a.blendLowAt,
            apiHighTemp = a.station?.high ?: row.apiHighTemp,
            apiLowTemp = a.station?.low ?: row.apiLowTemp,
            apiStationId = a.station?.stationId ?: row.apiStationId,
            apiStationDistanceKm = a.station?.distanceKm ?: row.apiStationDistanceKm,
            actualsSource = DailyActualsSource.NWS_STATION_PULL.storedValue,
            lastWriter = DailyHistoryWriter.NWS_STATION_PULL.storedValue,
            updatedAt = nowMs,
        ).takeIf { it.copy(updatedAt = row.updatedAt) != row }

    /**
     * A cache-derived day: the api actual only. The blend already comes from the stored pool via the
     * ordinary recompute, so overwriting it would only add churn. Null when unchanged.
     */
    fun applyCached(row: DailyHistory, s: StationDailyExtremes.StationDailyExtreme, nowMs: Long): DailyHistory? =
        row.copy(
            apiHighTemp = s.high,
            apiLowTemp = s.low,
            apiStationId = s.stationId,
            apiStationDistanceKm = s.distanceKm,
            actualsSource = DailyActualsSource.CACHED_OBSERVATIONS.storedValue,
            lastWriter = DailyHistoryWriter.CACHED_STATION_FALLBACK.storedValue,
            updatedAt = nowMs,
        ).takeIf { it.copy(updatedAt = row.updatedAt) != row }
}
