package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.util.TempUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The daily view's actuals: stored past rows, yesterday borrowed from a previous site, and today's
 * live blend, per source. Shared by Android's `DailyActualsStore` and desktop's
 * `DesktopWeatherRepository.loadDailyActuals`, which only do the reads and the logging.
 *
 * It used to be composed twice and the copies drifted: desktop never gated a late-starting today's
 * low ([TodayActualsCoverage]), resolved same-date fragments by row order instead of distance, and
 * let a persisted (lagging) today row stand in for the live blend.
 * See plans/261002-share-daily-actuals-assembly.md.
 */
object DailyActualsAssembler {

    /** A today low nulled because the source's rows do not reach back to the start of the day. */
    data class SuppressedLow(val source: String, val low: Float, val rows: Int)

    /** Today's live blend for one source, for the caller's diagnostic log line. */
    data class LiveSummary(val source: String, val high: Float?, val low: Float?, val rows: Int)

    data class Result(
        val bySource: Map<String, Map<LocalDate, DailyHistory>>,
        /** First/last timestamp of today's observations, or null when there are none. */
        val todayObsSpan: Pair<Long, Long>?,
        val todayObsRows: Int,
        val live: List<LiveSummary>,
        val suppressedTodayLows: List<SuppressedLow>,
    )

    /**
     * @param pastRows `daily_history` rows for the caller's lookback; rows dated today or later and
     *   rows outside the [LocationMatch] box are ignored.
     * @param yesterdayDonors measured rows for yesterday at any site ([PreviousSiteHistory]).
     * @param observations must span at least today plus [ActualsAggregator.DAILY_BLEND_CONTEXT_MS]
     *   before it; a wider span lets the live blend fill past days that have no stored row.
     */
    fun assemble(
        activeSources: List<WeatherSource>,
        pastRows: List<DailyHistory>,
        yesterdayDonors: List<DailyHistory>,
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        today: LocalDate,
        zone: ZoneId,
        nowMs: Long,
        personalStationWeight: Double,
    ): Result {
        val activeIds = activeSources.mapTo(HashSet()) { it.id }
        if (activeIds.isEmpty()) return Result(emptyMap(), null, 0, emptyList(), emptyList())

        // Today's live blend mixes stored OBSERVATIONS, so it stays gated on having a real feed — a
        // source must never fabricate a "current actual" from its own forecast. A forecast-only
        // source qualifies by BORROWING one (Silurian → METAR/Synoptic).
        val actualsCapable = activeSources
            .filter { ActualsProviderResolver.hasTemperatureActuals(it) }
            .mapTo(HashSet()) { it.id }
        // Filter on PROVIDER api, not source id: a borrowing source's rows arrive under METAR, which
        // is never an active display source.
        val providerIdBySource = activeSources.associate { it.id to ActualsProviderResolver.providerIdFor(it) }
        val observationApis = actualsCapable + actualsCapable.map { providerIdBySource.getValue(it) }

        // Past rows are kept for every active source: forecast-only rows (null computed*) carry the
        // frozen forecast the history columns label. Today is never read from storage — a persisted
        // today row lags the observation window.
        val local = pastRows
            .filter { it.source in activeIds && it.toLocalDate().isBefore(today) && inBox(it, latitude, longitude) }
            .groupBy { it.source }
            .mapValues { (_, rows) ->
                rows.groupBy { it.toLocalDate() }.mapValues { (_, sameDay) ->
                    sameDay.minBy { TempUtils.distanceSq(it.locationLat, it.locationLon, latitude, longitude) }
                }
            }
        val past = PreviousSiteHistory.fill(
            local = local,
            candidates = yesterdayDonors.filter { it.source in activeIds },
            lat = latitude,
            lon = longitude,
            today = today,
        )

        val blendObs = observations.filter { it.stationId != "NWS_BLEND" && it.api in observationApis }
        // Hourly stays keyed on the SOURCE: borrowed actuals are compared against the borrowing
        // source's own forecast.
        val blendHourly = hourlyForecasts.filter { it.source in actualsCapable }
        val liveBySource = ActualsAggregator.aggregate(
            observations = blendObs,
            hourlyForecasts = blendHourly,
            locationLat = latitude,
            locationLon = longitude,
            zoneId = zone,
            updatedAtMs = nowMs,
            personalStationWeight = personalStationWeight,
        ).groupBy { it.source }.mapValues { (_, rows) -> rows.associateBy { it.toLocalDate() } }

        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayObs = blendObs.filter { it.timestamp in todayStartMs until tomorrowMs }
        fun providerRows(source: String): List<Long> {
            val providerId = providerIdBySource[source]
                ?: ActualsProviderResolver.providerIdFor(WeatherSource.fromId(source))
            return todayObs.filter { it.api == providerId }.map { it.timestamp }
        }

        // A day's minimum is only the day's LOW if the day was watched from its start. Coverage is
        // judged against the PROVIDER's rows, so a borrowing source is not reported as rows=0.
        val suppressed = mutableListOf<SuppressedLow>()
        val gatedLive = liveBySource.mapValues { (source, byDate) ->
            val todayActual = byDate[today]
            val low = todayActual?.computedLowTemp ?: return@mapValues byDate
            val timestamps = providerRows(source)
            if (!TodayActualsCoverage.dayStartUncovered(timestamps, today, zone)) return@mapValues byDate
            suppressed += SuppressedLow(source, low, timestamps.size)
            byDate + (today to todayActual.copy(computedLowTemp = null))
        }

        // Stored past rows win; the live blend fills only what storage lacks (and today).
        val merged = (past.keys + gatedLive.keys).associateWith { source ->
            gatedLive[source].orEmpty() + past[source].orEmpty()
        }

        return Result(
            bySource = merged,
            todayObsSpan = todayObs.takeIf { it.isNotEmpty() }?.let { obs -> obs.minOf { it.timestamp } to obs.maxOf { it.timestamp } },
            todayObsRows = todayObs.size,
            live = liveBySource.keys.sorted().map { source ->
                val actual = liveBySource.getValue(source)[today]
                LiveSummary(source, actual?.computedHighTemp, actual?.computedLowTemp, providerRows(source).size)
            },
            suppressedTodayLows = suppressed,
        )
    }

    private fun inBox(row: DailyHistory, lat: Double, lon: Double): Boolean =
        abs(row.locationLat - lat) <= LocationMatch.TOLERANCE_DEG &&
            abs(row.locationLon - lon) <= LocationMatch.TOLERANCE_DEG

    private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Local wall-clock for a span bound, for log lines. */
    fun formatLocal(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(LOG_TIME)
}
