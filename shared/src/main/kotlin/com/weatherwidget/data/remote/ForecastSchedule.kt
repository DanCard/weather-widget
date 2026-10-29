package com.weatherwidget.data.remote


/**
 * When a forecast loop next has something to fetch, so it can sleep until then instead of checking on a
 * fixed tick (user, 2026-10-11; performance/261011-desktop-forecast-loop-sleep-until-due.md).
 *
 * Each source has one due time ([ForecastHorizonCadence]); the loop sleeps until the
 * earliest, capped at [MAX_SLEEP_MS] for changes it cannot hear, and re-plans on every wake — the
 * sleep is a forecast, the fetch decision is always "is it due now?". Pure: the desktop daemon's
 * forecast loop sleeps on it and Android enqueues its due-time sync from it.
 */
object ForecastSchedule {
    private const val MINUTE_MS = 60_000L

    /** Slack so a wake that lands a moment early still counts (same as [ForecastHorizonCadence.DUE_GRACE_MS]). */
    const val DUE_GRACE_MS = ForecastHorizonCadence.DUE_GRACE_MS

    /**
     * Longest sleep, Android and desktop: the longest forecast interval, so in normal operation it never
     * binds. Both platforms hear what moves a due time (power, screen, resume, network, moves) and a
     * screen-on heals a lost wake, so the cap only backstops a planner bug — it is not a poll
     * (2 h until 2026-10-28; plans/261028-screen-off-cadence-and-due-time-wakes.md).
     */
    const val MAX_SLEEP_MS = ForecastHorizonCadence.LOW_BATTERY_HOURS * 60 * MINUTE_MS

    /** Shortest sleep, so a due time already past cannot spin the loop. */
    const val MIN_SLEEP_MS = MINUTE_MS

    /** After a failed fetch the source is still due; wait this long before trying again (the old tick). */
    const val FAILURE_RETRY_MS = 15 * MINUTE_MS

    /** One source's next due instant. */
    data class Due(val sourceId: String, val atMs: Long)

    /**
     * When a part fetched at [lastMs] on an [intervalMs] cadence is next due: never when the cadence is
     * off (null), now when it was never fetched.
     */
    fun dueAtMs(lastMs: Long?, intervalMs: Long?, nowMs: Long): Long? {
        val interval = intervalMs ?: return null
        val last = lastMs ?: return nowMs
        return last + interval - DUE_GRACE_MS
    }

    /**
     * A source's next due instant held past its quota block: a source refused every product cannot be
     * fetched before [blockedUntilMs], so the loop sleeps until then rather than waking every
     * [MIN_SLEEP_MS] to plan a fetch it cannot make (performance/261012-forecast-loop-quota-blocked-part-is-not-due.md).
     */
    fun heldPast(dueAtMs: Long?, blockedUntilMs: Long?): Long? =
        dueAtMs?.let { due -> blockedUntilMs?.let { maxOf(due, it) } ?: due }

    fun isDue(lastMs: Long?, intervalMs: Long?, nowMs: Long): Boolean =
        dueAtMs(lastMs, intervalMs, nowMs)?.let { nowMs >= it } ?: false

    /** The earliest of [candidates], or null when nothing will come due under the current state. */
    fun next(candidates: List<Due>): Due? = candidates.minByOrNull { it.atMs }

    enum class Reason { DUE, CAP, RETRY }

    data class Sleep(val ms: Long, val reason: Reason, val next: Due?) {
        /** `FORECAST_LOOP_SLEEP` app_logs message. */
        fun logLine(): String =
            "sleepMin=${ms / MINUTE_MS} reason=$reason" +
                (next?.let { " next=${it.sourceId}" } ?: "")
    }

    /**
     * How long to sleep after a plan: until [next] (≥ [MIN_SLEEP_MS]), at most [MAX_SLEEP_MS]; after a
     * failed fetch ([fetchFailed]) at least [FAILURE_RETRY_MS] (network restored wakes the loop sooner).
     */
    fun sleep(next: Due?, nowMs: Long, fetchFailed: Boolean): Sleep {
        val untilDue = next?.let { (it.atMs - nowMs).coerceAtLeast(MIN_SLEEP_MS) }
        val floor = if (fetchFailed) FAILURE_RETRY_MS else MIN_SLEEP_MS
        return when {
            untilDue == null || untilDue >= MAX_SLEEP_MS -> Sleep(MAX_SLEEP_MS, Reason.CAP, next)
            untilDue < floor -> Sleep(floor, Reason.RETRY, next)
            else -> Sleep(untilDue, Reason.DUE, next)
        }
    }
}
