package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The stored daily blend recompute, one rule for both platforms: which days, each day's observation
 * and hourly windows, the unchanged-input skip, the read of the day's existing rows, the
 * [DailyBlendWrite] plan, its application and every `DAILY_RECOMPUTE_*` / `DAILY_HISTORY_BLEND` log.
 *
 * Was Android's `DailyActualsStore.recomputeDailyExtremesForDay` and desktop's
 * `recomputeDailyExtremes`, which blended one `now − 8 d` window: it wrote a row for the oldest,
 * partly covered day and read existing rows with the window's milliseconds against UTC-midnight
 * keys, so that day's row was missed, planned as an insert and replaced whole — every NWS day lost
 * its api actual, frozen forecast and true extremes as it aged out
 * (plans/261025-shared-daily-blend-recompute.md). One whole day at a time makes both impossible.
 */
object DailyBlendRecomputeRun {
    /** Days before today a recompute still reaches (user, 2026-10-25: Android's cutoff, both platforms). */
    const val MAX_DAYS_BACK = 9L

    /**
     * Only a genuinely slow day writes `DAILY_RECOMPUTE_PERF`: a range walks up to ten days per sync,
     * so a row per day would itself become log bloat.
     */
    const val SLOW_DAY_MS = 250L

    /**
     * Cap on tracked (day, site) signatures. Retention keeps ~10 days and a device visits few places,
     * so this is never reached in normal use; a pathological run of location changes cannot grow the
     * store without bound. Cleared wholesale — a miss costs one recompute.
     */
    const val MAX_TRACKED_DAYS = 256

    private const val DAY_MS = 86_400_000L

    interface Host {
        val lat: Double
        val lon: Double

        /** Read per day: the user can change it between recomputes. */
        fun personalStationWeight(): Double

        /** Retained observations in the read box over [startMs, endMs), every api. */
        suspend fun observations(startMs: Long, endMs: Long): List<ObservationReading>

        /** Live hourly rows at the site over [startMs, endMs], every source. */
        suspend fun hourlyAllSources(startMs: Long, endMs: Long): List<HourlyForecast>

        /**
         * Content signature of the observations in [startMs, endMs) at the site — count, newest
         * timestamp and the value sums the reduction reads — or null when it cannot be computed.
         */
        suspend fun observationSignature(startMs: Long, endMs: Long): String?

        /** Every `daily_history` row in the read box at the UTC-midnight key [dateKey]. */
        suspend fun dailyHistoryAt(dateKey: Long): List<DailyHistory>

        suspend fun insert(rows: List<DailyHistory>)

        /** [DailyBlendWrite.Update]'s conditional write; returns the rows updated (0 = changed meanwhile). */
        suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long): Int

        val signatures: ReducedSignatureStore

        suspend fun log(tag: String, message: String, level: String)

        /** Monotonic milliseconds, for the stage timings. */
        fun elapsedMs(): Long

        /** Platform diagnostics after a day is reduced (Android's opt-in `EXTREMA_WINDOW_DIAG`); default none. */
        suspend fun afterReduce(date: LocalDate, dayObservations: List<ObservationReading>, hourly: List<HourlyForecast>) {}
    }

    /** Every day of [startDate]..[endDateInclusive] within reach ([MAX_DAYS_BACK]); returns rows written. */
    suspend fun range(
        host: Host,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        force: Boolean = false,
    ): Int {
        val cutoff = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(MAX_DAYS_BACK)
        var written = 0
        var date = maxOf(startDate, cutoff)
        while (!date.isAfter(endDateInclusive)) {
            written += day(host, date, nowMs, zone, force)
            date = date.plusDays(1)
        }
        return written
    }

    /**
     * One local day: skipped when its observations are unchanged since it was last reduced (unless
     * [force], for the repair paths that call precisely because they distrust what is stored).
     * Returns rows written.
     */
    suspend fun day(
        host: Host,
        date: LocalDate,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        force: Boolean = false,
    ): Int {
        val startedMs = host.elapsedMs()
        val dateKey = date.toEpochDay() * DAY_MS
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // A day's extremes cannot move while its observations are unchanged (Samsung 2026-09-06: 91% of
        // per-day recomputes were byte-identical). Content-derived; `MAX(fetchedAt)` never fired.
        val key = if (force) null else signatureKey(date, host.lat, host.lon)
        val signature = if (force) null else host.observationSignature(dayStartMs, dayEndMs)
        if (key != null && signature != null && host.signatures[key] == signature) {
            host.log("DAILY_RECOMPUTE_SKIP", "date=$date sig=$signature at=${host.lat},${host.lon}", "INFO")
            return 0
        }

        val windowStartMs = dayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val windowEndMs = dayEndMs + ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextObs = host.observations(windowStartMs, windowEndMs)
        val afterQueryMs = host.elapsedMs()
        val dayObs = contextObs.filter { it.timestamp in dayStartMs until dayEndMs }
        if (dayObs.isEmpty()) return 0

        var hourly = emptyList<HourlyForecast>()
        var afterHourlyMs = afterQueryMs
        // apiHigh/apiLow are never derived here: the stored pool's NWS API subset is too thinly
        // sampled to carry a daily peak; NwsStationActualsRun's complete pull writes them.
        val newRows = ActualsAggregator
            .aggregateStored(
                observations = contextObs,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                readHourlyAllSources = { start, end ->
                    hourly = host.hourlyAllSources(start, end)
                    afterHourlyMs = host.elapsedMs()
                    hourly
                },
                locationLat = host.lat,
                locationLon = host.lon,
                zoneId = zone,
                updatedAtMs = nowMs,
                personalStationWeight = host.personalStationWeight(),
            )
            .filter { it.date == dateKey }
        val afterExtremesMs = host.elapsedMs()
        logBlendBreakdown(host, date, dayObs, newRows)
        val afterBreakdownMs = host.elapsedMs()
        host.afterReduce(date, dayObs, hourly)
        val afterDiagMs = host.elapsedMs()

        // Read here, immediately before the write: a concurrent station pull landing during the blend
        // must be seen, and the conditional update skips a row that changes after this read.
        val plan = DailyBlendWrite.plan(
            date = date,
            today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
            newRows = newRows,
            existingInBox = host.dailyHistoryAt(dateKey),
            latitude = host.lat,
            longitude = host.lon,
            dayReadings = dayObs,
            zone = zone,
        )
        plan.updates.forEach { update ->
            if (host.updateBlendIfUnchanged(update.merged, update.newUpdatedAt, update.expectedUpdatedAt) == 0) {
                DailyBlendWrite.raceLine(update, date).let { host.log(it.tag, it.message, it.level) }
            }
        }
        plan.logs.forEach { host.log(it.tag, it.message, it.level) }
        if (plan.inserts.isNotEmpty()) host.insert(plan.inserts)

        // Recorded only once the day is written, so a failure part way leaves it open.
        if (key != null && signature != null) {
            if (host.signatures.size >= MAX_TRACKED_DAYS) host.signatures.clear()
            host.signatures[key] = signature
        }
        val endMs = host.elapsedMs()
        if (endMs - startedMs >= SLOW_DAY_MS) {
            host.log(
                "DAILY_RECOMPUTE_PERF",
                "date=$date total=${endMs - startedMs}ms " +
                    "query=${afterQueryMs - startedMs}ms " +
                    "hourly=${afterHourlyMs - afterQueryMs}ms " +
                    "extremes=${afterExtremesMs - afterHourlyMs}ms " +
                    "logBreakdown=${afterBreakdownMs - afterExtremesMs}ms " +
                    "logDiag=${afterDiagMs - afterBreakdownMs}ms " +
                    "persist=${endMs - afterDiagMs}ms " +
                    "contextObs=${contextObs.size} dayObs=${dayObs.size} hourly=${hourly.size}",
                "INFO",
            )
        }
        return plan.inserts.size + plan.updates.size
    }

    /**
     * Quantized to the write grid so GPS jitter cannot mint unbounded keys for one place. Format kept
     * from Android's persisted store (`date|lat|lon` at 3 dp), so its stored signatures stay valid.
     */
    fun signatureKey(date: LocalDate, latitude: Double, longitude: Double): String =
        "$date|${"%.3f".format(latitude)}|${"%.3f".format(longitude)}"

    /**
     * The observation `api` whose rows built [rowSource]'s daily blend: the resolved actuals provider,
     * which for a redirected source is another feed entirely. Keying on the row's own id logged
     * Open-Meteo's Warsaw row (built from 8 Synoptic stations) as `stations=[OPEN_METEO_MAIN]`, 2026-09-28.
     */
    fun blendInputApi(
        rowSource: String,
        /** Null = the installed actuals-provider preferences. */
        preference: ((WeatherSource) -> WeatherSource?)? = null,
    ): String {
        val source = WeatherSource.entries.firstOrNull { it.id == rowSource } ?: return rowSource
        return if (preference == null) {
            ActualsProviderResolver.providerIdFor(source)
        } else {
            ActualsProviderResolver.providerIdFor(source, preference)
        }
    }

    private suspend fun logBlendBreakdown(host: Host, date: LocalDate, dayObs: List<ObservationReading>, rows: List<DailyHistory>) {
        val perApi = dayObs.groupBy { it.api }.mapValues { (_, apiObs) ->
            apiObs.groupBy { it.stationId }.entries.joinToString(",") { (stationId, stationObs) ->
                "$stationId(d=${"%.2f".format(stationObs.first().distanceKm)}km," +
                    "hi=${stationObs.maxOf { it.temperature }},lo=${stationObs.minOf { it.temperature }},n=${stationObs.size})"
            }
        }
        rows.forEach { row ->
            host.log(
                "DAILY_HISTORY_BLEND",
                "date=$date src=${row.source} computed_hi=${row.computedHighTemp} computed_lo=${row.computedLowTemp} " +
                    "stations=[${perApi[blendInputApi(row.source)] ?: "n/a"}] userLat=${host.lat} userLon=${host.lon}",
                "VERBOSE",
            )
        }
    }
}
