package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.HourlyOnDemand
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one source's fetch becomes in `hourly_forecasts`, `hourly_forecast_history` and `forecasts`, in
 * one order on both platforms (user 2026-10-24; `plans/261024-shared-forecast-persist-rules.md`):
 *
 * 1. The live hours ([HourlyForecastWrite]) over this site's stored rows.
 * 2. The fetch's snapshot, if [Host.snapshotLabel] keeps one, and under the same label the payload's
 *    elapsed hours where the site has none ([ElapsedForecastBackfill]).
 * 3. The daily rows ([DailyForecastWrite]): every day from today, each day's summaries from the whole download, else the row as sent, else the hours stored
 *    now (step 1's, plus hours a Google one-page fetch left in place) — [DailyHourlySummaries].
 *
 * Quota banners, horizon markers and observations stay with the caller; the Host reads and writes rows.
 */
class ForecastPersistRun<K>(private val host: Host<K>) {
    /** One source at one site; every read is on the site's exact quantized key. */
    interface Host<K> {
        val sourceId: String
        val latitude: Double
        val longitude: Double

        /** This source's live rows in [startMs]..[endMs]. */
        suspend fun storedHourly(startMs: Long, endMs: Long): List<HourlyForecast>

        /** Writes [rows] live, as given (fetchedAt included). */
        suspend fun writeHourly(rows: List<HourlyForecast>)

        /** The fetch's `com.weatherwidget.shared.history.HourlySnapshotRun` label, or null to keep none. */
        suspend fun snapshotLabel(nowMs: Long): Long?

        suspend fun writeSnapshot(label: Long, rows: List<HourlyForecast>)

        /** The elapsed-hour backfill's store, filing new rows under [label]. */
        fun elapsedStore(label: Long, nowMs: Long): ElapsedForecastBackfill.Store

        /** This source's daily rows dated [from]..[to]. */
        suspend fun storedDaily(from: LocalDate, to: LocalDate): List<DailyForecastWrite.Stored<K>>

        /** [write] as new rows (`dateOfPrediction` = today, both stamps [nowMs]); [restamp] take `lastConfirmedAt` = [nowMs]. */
        suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<K>, nowMs: Long)

        suspend fun log(tag: String, message: String, level: String)
    }

    /**
     * [hourly]: the download's hours, elapsed ones included; [elapsedHourly]: elapsed hours a provider
     * sends apart (NWS's grid). [daily]: null when the daily product was not asked for — a source that
     * cannot split still sends it, and it is dropped. [keep]: the fetch's live and snapshot horizon.
     */
    data class Fetch(
        val hourly: List<HourlyForecast>,
        val elapsedHourly: List<HourlyForecast> = emptyList(),
        val daily: List<DailyForecast>?,
        val keep: HourlyOnDemand.KeepUntil?,
        val userRefresh: Boolean = false,
    )

    data class Result(
        val liveHours: Int,
        val snapshotLabel: Long?,
        val snapshotHours: Int,
        val elapsed: ElapsedForecastBackfill.Summary?,
        val dailyWritten: Int,
        val dailyRestamped: Int,
    )

    suspend fun persist(fetch: Fetch, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val source = host.sourceId
        val hourlyPlan = HourlyForecastWrite.readRange(fetch.hourly)?.let { range ->
            HourlyForecastWrite.plan(fetch.hourly, host.storedHourly(range.first, range.last), source, nowMs, fetch.keep)
        }
        hourlyPlan?.trimLine?.let { host.log(HourlyOnDemand.TRIM_TAG, it, "VERBOSE") }
        if (!hourlyPlan?.live.isNullOrEmpty()) host.writeHourly(hourlyPlan!!.live)

        val history = fetch.hourly + fetch.elapsedHourly
        val label = if (history.isEmpty()) null else host.snapshotLabel(nowMs)
        val snapshot = hourlyPlan?.snapshot.orEmpty()
        if (label != null && snapshot.isNotEmpty()) host.writeSnapshot(label, snapshot)
        val elapsed = label?.let { backfillElapsed(history, it, nowMs) }

        val daily = fetch.daily?.let { persistDaily(it, history, fetch.userRefresh, nowMs, zone) }
        return Result(
            liveHours = hourlyPlan?.live?.size ?: 0,
            snapshotLabel = label,
            snapshotHours = if (label == null) 0 else snapshot.size,
            elapsed = elapsed,
            dailyWritten = daily?.write?.size ?: 0,
            dailyRestamped = daily?.restamp?.size ?: 0,
        )
    }

    /** Never fails the fetch: the live rows and the daily rows matter more than history for a blank site. */
    private suspend fun backfillElapsed(hours: List<HourlyForecast>, label: Long, nowMs: Long): ElapsedForecastBackfill.Summary? {
        val summary = try {
            ElapsedForecastBackfill.backfill(hours, nowMs, host.elapsedStore(label, nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("HOURLY_HISTORY_BACKFILL_FAIL", "source=${host.sourceId} ${e.javaClass.simpleName}: ${e.message}", "WARN")
            return null
        } ?: return null
        host.log(
            ElapsedForecastBackfill.LOG_TAG,
            ElapsedForecastBackfill.logMessage(host.sourceId, host.latitude, host.longitude, summary),
            ElapsedForecastBackfill.logLevel(summary),
        )
        return summary
    }

    private suspend fun persistDaily(
        daily: List<DailyForecast>,
        payload: List<HourlyForecast>,
        userRefresh: Boolean,
        nowMs: Long,
        zone: ZoneId,
    ): DailyForecastWrite.Plan<K>? {
        val source = host.sourceId
        val today = PredictionDate.of(nowMs, zone)
        val dated = daily.mapNotNull { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
            (day to date).takeIf { !date.isBefore(today) }
        }
        if (dated.isEmpty()) return null

        DailyHourlyConsistency.check(dated.map { (day, date) -> DailyHourlyConsistency.Day(date, day.highTemp, day.lowTemp) }, payload, today, zone)
            .takeIf { it.isNotEmpty() }
            ?.let { host.log(DailyHourlyConsistency.LOG_TAG, DailyHourlyConsistency.logMessage(source, it), "WARN") }

        val stored = host.storedHourly(
            DailyPrecipPeriods.readStartMs(dated.minOf { it.second }),
            DailyPrecipPeriods.readEndMs(dated.maxOf { it.second }),
        )
        val incoming = dated.map { (day, date) ->
            val fromPayload = DailyHourlySummaries.forDate(payload, date, source, zone)
            val summary = DailyHourlySummaries.carryForward(
                DailyHourlySummaries.carryForward(fromPayload, day.hourlySummary),
                if (stored.isEmpty()) null else DailyHourlySummaries.forDate(stored, date, source, zone),
            )
            DailyForecastWrite.Incoming(
                date = date,
                highTemp = day.highTemp,
                lowTemp = day.lowTemp,
                condition = day.condition,
                iconToken = day.iconToken,
                precipProbability = day.precipProbability,
                precipAmountMm = day.precipAmountMm,
                daytimePrecipProbability = day.daytimePrecipProbability,
                nighttimePrecipProbability = day.nighttimePrecipProbability,
                summary = summary,
                isClimateNormal = day.isClimateNormal,
                periodEndMs = day.periodEndMs,
            )
        }
        host.log(
            "DAILY_SUMMARY",
            "source=$source site=${LocationMatch.quantize(host.latitude)},${LocationMatch.quantize(host.longitude)} " +
                "days=${incoming.size} noon=${incoming.count { it.summary.noonCloudPercent != null }} " +
                "day=${incoming.count { it.summary.dayPrecipMax != null }} " +
                "night=${incoming.count { it.summary.nightPrecipMax != null }}",
            "VERBOSE",
        )

        val range = DailyForecastWrite.readRange(incoming, nowMs, zone)
        val storedDaily = range?.let { host.storedDaily(it.start, it.endInclusive) }.orEmpty()
        val plan = DailyForecastWrite.plan(incoming, storedDaily, source, nowMs, lastConfirmedAt = nowMs, userRefresh = userRefresh, zone = zone)
        plan.lines.forEach { host.log(it.tag, it.message, it.level) }
        if (plan.write.isNotEmpty() || plan.restamp.isNotEmpty()) host.writeDaily(plan.write, plan.restamp, nowMs)
        return plan
    }
}
