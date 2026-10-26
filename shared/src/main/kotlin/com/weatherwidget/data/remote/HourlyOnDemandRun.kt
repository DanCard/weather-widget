package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * One on-demand hourly fetch for a tapped or panned-to day, the same way on both platforms: re-check
 * [HourlyOnDemand.hoursToCover] against what is stored *now* (a tap's fetch, a pan's or a quiet
 * gap-fill may have stored it since the decision), fetch the source alone, and report one
 * [TAG] line. The platform owns what surrounds it — Android's banner and one-widget paint, desktop's
 * reload. Was Android's `HourlyOnDemandWorker` and desktop's `extendHourlyFor`: Android fetched
 * without the re-check and printed different fields
 * (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 */
object HourlyOnDemandRun {
    const val TAG = "HOURLY_ON_DEMAND"

    interface Host {
        /** The site the rows must be at to count ([HourlyOnDemand.hoursToCover]). */
        val lat: Double
        val lon: Double

        /** This source's stored hourly at the site, current hour through [HourlyOnDemand.REACH_HOURS]. */
        suspend fun storedHourly(): List<HourlyForecast>

        /** Fetch and store [request]'s source alone; true when it stored hours. */
        suspend fun fetch(request: HourlyOnDemand.Request): Boolean
    }

    enum class Outcome(val logValue: String) {
        COVERED("covered_no_fetch"),
        STORED("stored"),
        EMPTY("empty"),
        FAILED("failed"),
    }

    data class Result(
        val outcome: Outcome,
        val hours: Int?,
        val storedRows: Int,
        val storedLastMs: Long?,
        val fetchMs: Long,
        val error: String? = null,
    ) {
        val stored: Boolean get() = outcome == Outcome.STORED
    }

    /** Never throws but for cancellation: a failed fetch is [Outcome.FAILED], for the caller's banner to settle. */
    suspend fun run(host: Host, sourceId: String, date: LocalDate, zone: ZoneId, nowMs: Long): Result {
        val stored = host.storedHourly()
        val storedLast = stored.maxOfOrNull { it.dateTime }
        val hours = HourlyOnDemand.hoursToCover(sourceId, date, zone, nowMs, stored, host.lat, host.lon)
            ?: return Result(Outcome.COVERED, null, stored.size, storedLast, 0L)
        val start = System.nanoTime()
        val outcome: Outcome
        try {
            outcome = if (host.fetch(HourlyOnDemand.Request(sourceId, hours))) Outcome.STORED else Outcome.EMPTY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(Outcome.FAILED, hours, stored.size, storedLast, (System.nanoTime() - start) / 1_000_000, "${e::class.simpleName}:${e.message}")
        }
        return Result(outcome, hours, stored.size, storedLast, (System.nanoTime() - start) / 1_000_000)
    }

    /** The one [TAG] line; [extra] carries the platform's own timings (Android: widget, paint). */
    fun line(sourceId: String, date: LocalDate, result: Result, extra: String = ""): String =
        "source=$sourceId date=$date outcome=${result.outcome.logValue} hours=${result.hours ?: "-"} " +
            "storedRows=${result.storedRows} storedLast=${result.storedLastMs ?: "-"} fetchMs=${result.fetchMs}" +
            (result.error?.let { " error=$it" } ?: "") +
            (if (extra.isNotEmpty()) " $extra" else "")

    /** WARN for a failure, INFO otherwise. */
    fun level(result: Result): String = if (result.outcome == Outcome.FAILED) "WARN" else "INFO"
}
