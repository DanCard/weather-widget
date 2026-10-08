package com.weatherwidget.data.remote

/**
 * Whether a forecast fetch includes the hourly product, for providers that bill it separately
 * (Google `forecast/hours`: 3 pages per fetch against a per-project daily quota).
 *
 * User's rule, 2026-10-08: the screen coming on, a wake or network restore, and cycling sources in the
 * daily view refresh the daily forecast, current temperature and actuals, but not the hourly
 * forecast. Those fetches are *hourly-limited*; every other fetch (the scheduled cadence, an explicit
 * refresh, a toggle while an hourly view is showing) is not.
 *
 * A limited fetch still takes the hours once they are themselves due by the normal cadence: the
 * cadence is judged from the source's daily rows, so a daily-only refresh makes the source look fresh,
 * and without this the scheduled fetch that would have brought the hours never comes.
 */
object HourlyFetchGate {
    /**
     * @param newestHourlyFetchedAtMs newest `fetchedAt` among the source's stored hourly rows at the
     *   site; null when there are none.
     * @param cadenceMs the source's normal forecast interval now; null when the battery tier suspends
     *   background fetching.
     */
    fun includeHours(
        hourlyLimited: Boolean,
        newestHourlyFetchedAtMs: Long?,
        cadenceMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (!hourlyLimited) return true
        val newest = newestHourlyFetchedAtMs ?: return true
        val cadence = cadenceMs ?: return false
        return nowMs - newest >= cadence
    }
}
