package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import java.time.LocalDate
import java.time.ZoneId

/**
 * Platform-neutral orchestration for the NWS station-actuals pull that both Android
 * (`NwsApiDailyActualsFetcher`) and desktop (`DesktopWeatherRepository.fillNwsStationActualsIfNeeded`)
 * run after a fetch.
 *
 * The day-by-day fetch/coverage rules already live in [NwsDailyExtremesFetch]; this object owns the
 * surrounding *policy* that the two platforms had duplicated: split the per-date outcomes into
 * pulled actuals vs. `Insufficient` dates, fall back to stored observations **only** for
 * `Insufficient` (never `Unavailable`, which is retryable), and count the rest for the outcome log.
 *
 * Each platform still owns its DAO reads, row mapping, persistence and log sink.
 */
object NwsStationActualsMaintenance {
    /** Result of [resolve]: the two maps to persist plus the counters for the outcome log. */
    data class Outcome(
        /** Raw per-date outcomes; empty when every requested date fell outside the lookback window. */
        val outcomes: Map<Long, NwsDailyExtremesFetch.DayOutcome>,
        /** Dates whose live pull produced a blend (and possibly a station extreme). */
        val pulled: Map<Long, NwsDailyExtremesFetch.DailyActualsFromStations>,
        /** Dates that fell back to an extreme derived from our stored observations. */
        val cached: Map<Long, StationDailyExtremes.StationDailyExtreme>,
        /** `Insufficient` dates with no usable stored fallback — still unresolved after this pass. */
        val insufficientUnresolved: Int,
        /** Dates with at least one failed request; retryable, so never cached. */
        val unavailable: Int,
    )

    /**
     * Resolves every [missingDates] entry against the live station endpoint.
     *
     * @param fetchStationDay returns `null` when the request failed and an empty list when it
     *   succeeded with nothing to report — the two lead to `Unavailable` vs `Insufficient`.
     * @param stationExtremeFromStoredObservations derives a station extreme from our retained
     *   observations for a date; only called for `Insufficient` dates.
     */
    suspend fun resolve(
        missingDates: List<Long>,
        stationIdsNearestFirst: List<String>,
        userLat: Double,
        userLon: Double,
        personalStationWeight: Double,
        zone: ZoneId,
        nowMs: Long,
        hourlyForecastsForDay: suspend (dayStartMs: Long, dayEndMs: Long) -> List<HourlyForecast> = { _, _ -> emptyList() },
        fetchStationDay: suspend (stationId: String, startIso: String, endIso: String) -> List<ObservationReading>?,
        stationExtremeFromStoredObservations: suspend (date: LocalDate) -> StationDailyExtremes.StationDailyExtreme?,
    ): Outcome {
        val resolved = NwsDailyExtremesFetch.resolveForDates(
            datesEpochDayMs = missingDates,
            stationIdsNearestFirst = stationIdsNearestFirst,
            userLat = userLat,
            userLon = userLon,
            personalStationWeight = personalStationWeight,
            zone = zone,
            nowMs = nowMs,
            hourlyForecastsForDay = hourlyForecastsForDay,
            fetchStationDay = fetchStationDay,
        )
        val pulled = resolved.mapNotNull { (date, outcome) ->
            (outcome as? NwsDailyExtremesFetch.DayOutcome.Resolved)?.let { date to it.actuals }
        }.toMap()
        // Only Insufficient falls back. Unavailable means a request failed, so the date stays in
        // the missing set and retries rather than locking in a cached value over a live one.
        val insufficient = resolved
            .filterValues { it is NwsDailyExtremesFetch.DayOutcome.Insufficient }
            .keys
        val cached = insufficient.mapNotNull { dateMs ->
            val date = LocalDate.ofEpochDay(dateMs / 86_400_000L)
            stationExtremeFromStoredObservations(date)?.let { dateMs to it }
        }.toMap()
        return Outcome(
            outcomes = resolved,
            pulled = pulled,
            cached = cached,
            insufficientUnresolved = insufficient.size - cached.size,
            unavailable = resolved.count { it.value is NwsDailyExtremesFetch.DayOutcome.Unavailable },
        )
    }
}
