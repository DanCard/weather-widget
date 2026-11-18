package com.weatherwidget.shared.actuals

import com.weatherwidget.shared.observations.NwsBlend
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
        /** What the live blend cost, for the platform's perf line. */
        val blend: ActualsAggregator.AggregateStats = ActualsAggregator.AggregateStats(),
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
        val scope = Scope.of(activeSources) ?: return Result(emptyMap(), null, 0, emptyList(), emptyList())
        val activeIds = scope.activeIds
        val actualsCapable = scope.actualsCapable
        val providerIdBySource = scope.providerIdBySource

        // Today is never read from storage here — a persisted today row lags the observation
        // window. [assembleStored] is the provisional exception, replaced by this within seconds.
        val past = pastRows(scope, pastRows, yesterdayDonors, latitude, longitude, today)

        val blendObs = observations.filter { it.stationId != NwsBlend.STATION_ID && it.api in scope.observationApis }
        // Hourly stays keyed on the SOURCE: borrowed actuals are compared against the borrowing
        // source's own forecast.
        val blendHourly = hourlyForecasts.filter { it.source in actualsCapable }
        val blendStats = ActualsAggregator.AggregateStats()
        val liveBySource = ActualsAggregator.aggregate(
            observations = blendObs,
            hourlyForecasts = blendHourly,
            locationLat = latitude,
            locationLon = longitude,
            zoneId = zone,
            updatedAtMs = nowMs,
            personalStationWeight = personalStationWeight,
            // Today is always live. A past date is blended only where storage has no row for that
            // source: `merged` below lets the stored row win, so a live one beside it is never read.
            wanted = { source, date -> date == today || past[source]?.containsKey(date) != true },
            stats = blendStats,
            // Only the caller's sources: a source that borrows one of their providers is not on
            // screen, and past rows are already limited to activeIds. Android's DailyActualsCoverage
            // reloads a repaint whose source falls outside this filter.
            sources = activeIds,
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

        // Today's observed fog, live like today's blend and from the same provider rows (the stored
        // today row lags the window). plans/261111-daily-bar-morning-fog.md
        val withTodayFog = gatedLive.mapValues { (source, byDate) ->
            val todayActual = byDate[today] ?: return@mapValues byDate
            val providerId = providerIdBySource[source] ?: ActualsProviderResolver.providerIdFor(WeatherSource.fromId(source))
            val fog = com.weatherwidget.shared.fog.ObservedMorningFog.forDate(
                observations.filter { it.api == providerId }, today, zone, nowMs,
            )
            byDate + (today to todayActual.withObservedFog(fog))
        }

        // Stored past rows win; the live blend fills only what storage lacks (and today).
        val merged = (past.keys + withTodayFog.keys).associateWith { source ->
            withTodayFog[source].orEmpty() + past[source].orEmpty()
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
            blend = blendStats,
        )
    }

    /** Which of the caller's sources may carry actuals, and from which provider's rows. */
    private class Scope(
        val activeIds: Set<String>,
        /**
         * Today's actual mixes stored OBSERVATIONS, so it stays gated on having a real feed — a
         * source must never fabricate a "current actual" from its own forecast. A forecast-only
         * source qualifies by BORROWING one (Silurian → METAR/Synoptic).
         */
        val actualsCapable: Set<String>,
        val providerIdBySource: Map<String, String>,
        /** PROVIDER apis, not source ids: a borrowing source's rows arrive under its provider's api. */
        val observationApis: Set<String>,
    ) {
        companion object {
            fun of(activeSources: List<WeatherSource>): Scope? {
                val activeIds = activeSources.mapTo(HashSet()) { it.id }
                if (activeIds.isEmpty()) return null
                val actualsCapable = activeSources
                    .filter { ActualsProviderResolver.hasTemperatureActuals(it) }
                    .mapTo(HashSet()) { it.id }
                val providerIdBySource = activeSources.associate { it.id to ActualsProviderResolver.providerIdFor(it) }
                return Scope(
                    activeIds,
                    actualsCapable,
                    providerIdBySource,
                    actualsCapable + actualsCapable.map { providerIdBySource.getValue(it) },
                )
            }
        }
    }

    /**
     * Stored rows before [today], nearest in the box per (source, date), plus yesterday from a
     * previous site. Kept for every active source: forecast-only rows (null computed*) carry the
     * frozen forecast the history columns label.
     */
    private fun pastRows(
        scope: Scope,
        pastRows: List<DailyHistory>,
        yesterdayDonors: List<DailyHistory>,
        latitude: Double,
        longitude: Double,
        today: LocalDate,
    ): Map<String, Map<LocalDate, DailyHistory>> {
        val local = nearestPerSourceAndDate(
            pastRows.filter { it.source in scope.activeIds && it.toLocalDate().isBefore(today) },
            latitude,
            longitude,
        )
        return PreviousSiteHistory.fill(
            local = local,
            candidates = yesterdayDonors.filter { it.source in scope.activeIds },
            lat = latitude,
            lon = longitude,
            today = today,
        )
    }

    private fun nearestPerSourceAndDate(rows: List<DailyHistory>, latitude: Double, longitude: Double) = rows
        .filter { inBox(it, latitude, longitude) }
        .groupBy { it.source }
        .mapValues { (_, bySource) ->
            bySource.groupBy { it.toLocalDate() }.mapValues { (_, sameDay) ->
                sameDay.minBy { TempUtils.distanceSq(it.locationLat, it.locationLon, latitude, longitude) }
            }
        }

    /** Why a source's stored today row was not used for a provisional paint. */
    data class StoredTodayRejected(val source: String, val reason: String)

    /** [assembleStored]'s result: [Result] plus which stored today rows were refused, for its log line. */
    data class StoredResult(val result: Result, val rejected: List<StoredTodayRejected>)

    /**
     * The provisional startup paint's actuals: [assemble]'s rules with today read from STORAGE
     * instead of blended, so the first paint does not wait for the blend (13.2 s of a 20 s cold
     * start on the Pixel 7 Pro, 2026-11-18, where the stored rows equalled the live blend to the
     * last digit). The live [assemble] replaces it within seconds.
     *
     * A source's stored today row is painted as it stands: real measured data from the last
     * recompute, replaced by the live blend seconds later (user decision 2026-11-18). A freshness
     * gate refused it on nearly every cold start, because some feed reports every few minutes. When
     * the source's provider has no rows today, today has no actual: the forecast never stands in.
     * The late-start low gate is applied exactly as in [assemble]. Past days with no stored row
     * stay empty here (the live blend fills them).
     *
     * @param pastRows `daily_history` rows for the lookback THROUGH today.
     * @param todayObservations today's observations for the active sources' providers.
     */
    fun assembleStored(
        activeSources: List<WeatherSource>,
        pastRows: List<DailyHistory>,
        yesterdayDonors: List<DailyHistory>,
        todayObservations: List<ObservationReading>,
        latitude: Double,
        longitude: Double,
        today: LocalDate,
        zone: ZoneId,
    ): StoredResult {
        val scope = Scope.of(activeSources)
            ?: return StoredResult(Result(emptyMap(), null, 0, emptyList(), emptyList()), emptyList())
        val past = pastRows(scope, pastRows, yesterdayDonors, latitude, longitude, today)

        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayObs = todayObservations.filter {
            it.stationId != NwsBlend.STATION_ID && it.api in scope.observationApis && it.timestamp in todayStartMs until tomorrowMs
        }
        val storedToday = nearestPerSourceAndDate(
            pastRows.filter { it.source in scope.actualsCapable && it.toLocalDate() == today },
            latitude,
            longitude,
        ).mapNotNull { (source, byDate) -> byDate[today]?.let { source to it } }.toMap()

        val rejected = mutableListOf<StoredTodayRejected>()
        val suppressed = mutableListOf<SuppressedLow>()
        val todayBySource = HashMap<String, DailyHistory>()
        val providerTimestamps = scope.actualsCapable.associateWith { source ->
            val providerId = scope.providerIdBySource.getValue(source)
            todayObs.filter { it.api == providerId }.map { it.timestamp }
        }
        for (source in scope.actualsCapable) {
            val timestamps = providerTimestamps.getValue(source)
            val row = storedToday[source]
            when {
                row == null -> rejected += StoredTodayRejected(source, "no_stored_row")
                timestamps.isEmpty() -> rejected += StoredTodayRejected(source, "no_provider_rows")
                else -> {
                    val shaped = liveShaped(row, latitude, longitude)
                    val low = shaped.computedLowTemp
                    todayBySource[source] =
                        if (low != null && TodayActualsCoverage.dayStartUncovered(timestamps, today, zone)) {
                            suppressed += SuppressedLow(source, low, timestamps.size)
                            shaped.copy(computedLowTemp = null)
                        } else {
                            shaped
                        }
                }
            }
        }

        val merged = (past.keys + todayBySource.keys).associateWith { source ->
            // Today first, as [assemble] orders it.
            listOfNotNull(todayBySource[source]?.let { today to it }).toMap() + past[source].orEmpty()
        }
        return StoredResult(
            Result(
                bySource = merged,
                todayObsSpan = todayObs.takeIf { it.isNotEmpty() }?.let { obs -> obs.minOf { it.timestamp } to obs.maxOf { it.timestamp } },
                todayObsRows = todayObs.size,
                live = todayBySource.keys.sorted().map { source ->
                    val row = todayBySource.getValue(source)
                    LiveSummary(source, row.computedHighTemp, row.computedLowTemp, providerTimestamps.getValue(source).size)
                },
                suppressedTodayLows = suppressed,
            ),
            rejected,
        )
    }

    /**
     * A stored today row reduced to what [ActualsAggregator.aggregate] builds for today, so the
     * provisional paint shows nothing the live pass would not: the archival columns (frozen
     * forecasts, API extremes, provenance, the quantized site key) stay in storage.
     */
    private fun liveShaped(row: DailyHistory, latitude: Double, longitude: Double) = DailyHistory(
        date = row.date,
        source = row.source,
        locationLat = latitude,
        locationLon = longitude,
        computedHighTemp = row.computedHighTemp,
        computedLowTemp = row.computedLowTemp,
        condition = row.condition,
        updatedAt = row.updatedAt,
        precipAmountMm = row.precipAmountMm,
        precipDayMm = row.precipDayMm,
        precipNightMm = row.precipNightMm,
        computedHighAt = row.computedHighAt,
        computedLowAt = row.computedLowAt,
        observedFogLevel = row.observedFogLevel,
        observedFogTopTemp = row.observedFogTopTemp,
        observedFogVisibilityMi = row.observedFogVisibilityMi,
        observedFogPredawn = row.observedFogPredawn,
    )

    private fun inBox(row: DailyHistory, lat: Double, lon: Double): Boolean =
        abs(row.locationLat - lat) <= LocationMatch.TOLERANCE_DEG &&
            abs(row.locationLon - lon) <= LocationMatch.TOLERANCE_DEG

    private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Local wall-clock for a span bound, for log lines. */
    /** Today's observation span for a log line ("06:10..14:55"), or "none". */
    fun spanSummary(result: Result, zone: ZoneId): String =
        result.todayObsSpan?.let { (first, last) -> "${formatLocal(first, zone)}..${formatLocal(last, zone)}" } ?: "none"

    /**
     * The `TODAY_LOW_UNCOVERED` app_logs line per suppressed low, one format on both platforms:
     * (tag, message, level).
     */
    fun suppressedLowLines(result: Result, latitude: Double, longitude: Double, zone: ZoneId): List<Triple<String, String, String>> =
        result.suppressedTodayLows.map {
            Triple(
                "TODAY_LOW_UNCOVERED",
                "source=${it.source} span=${spanSummary(result, zone)} suppressedLow=${it.low} rows=${it.rows} at=$latitude,$longitude",
                "DEBUG",
            )
        }

    fun formatLocal(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(LOG_TIME)
}
