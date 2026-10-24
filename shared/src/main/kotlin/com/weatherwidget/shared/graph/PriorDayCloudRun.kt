package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cloud graph's frozen forecast curve — Open-Meteo's day-ago cloud predictions
 * ([PriorDayCloudForecast]) — fetched and stored the same way on both platforms. Android rode its
 * Open-Meteo fetch, desktop its refresh of any source; the throttle, window, gate and logs were
 * written twice (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 *
 * Throttled to once an hour: the prediction for an elapsed hour never changes. Best-effort — a
 * failure leaves the graph drawing the live value on both curves, which marks itself unfrozen.
 */
object PriorDayCloudRun {
    const val INTERVAL_MS = 60 * 60 * 1000L

    /** The cloud graph's 30 days back, plus today. */
    const val PAST_DAYS = 31

    interface Host {
        /** The last attempt (success or not); 0 when never. */
        var lastAttemptMs: Long

        suspend fun fetch(pastDays: Int, nowMs: Long): Map<Long, Int>

        suspend fun store(coverByHour: Map<Long, Int>, nowMs: Long)

        suspend fun log(tag: String, message: String, level: String)
    }

    /** The once-an-hour throttle's memory, kept by the caller across Hosts (one per call and site). */
    class LastAttempt {
        var ms = 0L
    }

    /** Runs when [sourceId] is being fetched and is the one source with a previous-runs product. */
    suspend fun run(host: Host, sourceId: String, nowMs: Long) {
        if (sourceId != WeatherSource.OPEN_METEO.id) return
        if (nowMs - host.lastAttemptMs < INTERVAL_MS) return
        // The attempt, not the success: an empty or failing product would otherwise retry every fetch.
        host.lastAttemptMs = nowMs
        try {
            val byHour = host.fetch(PAST_DAYS, nowMs)
            if (byHour.isEmpty()) {
                // Loud on purpose: at the render an empty result looks like a healthy graph (the curve
                // falls back to the live row, unfrozen) — cloud_cover_low_previous_day1 served
                // nothing for a week and nothing said so (2026-08-27).
                val variable = com.weatherwidget.data.remote.OpenMeteoApi.PREVIOUS_RUNS_VARIABLE
                host.log("PRIOR_CLOUD_EMPTY", "no usable hours from $variable; frozen forecast curve falls back to live values", "WARN")
                return
            }
            host.store(byHour, nowMs)
            host.log("PRIOR_CLOUD", "stored hours=${byHour.size} pastDays=$PAST_DAYS", "INFO")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("PRIOR_CLOUD_FAIL", "cloud graph falls back to live values: ${e.javaClass.simpleName}", "WARN")
        }
    }
}
