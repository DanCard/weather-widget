package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import kotlin.math.abs

/**
 * Whether a Google `forecast/hours` fetch needs pages 2–3 after page 1.
 *
 * Every page is one billed request against `ForecastHoursQueriesPerDay`, a per-project daily quota
 * shared by every device; 72 h at 24 per page made each full fetch cost 3 (spent by 03:16 on
 * 2026-10-07). Page 1 covers the next 24 h. When it matches what is stored for those hours, the run
 * has not moved and the stored hours 25–72 stand: saving is an upsert per hour, so they stay in place
 * with their older `fetchedAt`. Otherwise fetch the rest. User's design, 2026-10-07: "one call and if
 * no change stop; if change then continue".
 *
 * "Matches" needs tolerances, not equality: a fresh run nudges values every hour.
 */
object GoogleHourPaging {
    const val TEMP_TOLERANCE_F = 1f
    const val PRECIP_TOLERANCE_POINTS = 10
    const val MAX_CONDITION_CHANGES = 2

    /** Hours 25–72 are refetched at least this often even when page 1 keeps matching. */
    const val MAX_TAIL_AGE_MS = 12 * 3_600_000L

    /**
     * The tail must reach this close to the requested horizon to count as covering it. The stored tail
     * ends 72 h after the *previous* fetch, so it is short by exactly the time since then; at 2 h every
     * fetch on the 4 h+ cadence paid all 3 pages (`tail_short` on 13 of 14 fetches, 2026-10-08). The
     * same 12 h as [MAX_TAIL_AGE_MS]: a tail that is not too old is never more than that short.
     */
    private const val HORIZON_SLACK_MS = MAX_TAIL_AGE_MS

    data class Decision(val fetchRest: Boolean, val reason: String)

    /**
     * @param page1 parsed hours of the first page.
     * @param stored this source's stored hourly rows at the site (any `fetchedAt`).
     * @param horizonEndMs the end of the requested horizon (now + requested hours).
     */
    fun decide(
        page1: List<HourlyForecast>,
        stored: List<HourlyForecast>,
        nowMs: Long,
        horizonEndMs: Long,
    ): Decision {
        if (page1.isEmpty()) return Decision(true, "page1_empty")
        if (stored.isEmpty()) return Decision(true, "no_cache")
        val page1End = page1.maxOf { it.dateTime }
        val tail = stored.filter { it.dateTime > page1End }
        val tailEnd = tail.maxOfOrNull { it.dateTime }
        if (tailEnd == null || tailEnd < horizonEndMs - HORIZON_SLACK_MS) {
            return Decision(true, "tail_short tailEnd=$tailEnd")
        }
        // The newest write in the tail marks the last fetch that covered it. Not the oldest: the
        // Android save skips rows whose values did not change, so an untouched hour keeps an old
        // fetchedAt and would make the tail look stale forever.
        val tailFetchedAt = tail.maxOf { it.fetchedAt }
        val tailAgeMin = (nowMs - tailFetchedAt) / 60_000L
        if (nowMs - tailFetchedAt > MAX_TAIL_AGE_MS) return Decision(true, "tail_old tailAgeMin=$tailAgeMin")

        val storedByHour = stored.associateBy { it.dateTime }
        var overlap = 0
        var maxDt = 0f
        var maxDp = 0
        var conditionChanges = 0
        for (hour in page1) {
            val old = storedByHour[hour.dateTime] ?: continue
            overlap++
            maxDt = maxOf(maxDt, abs(hour.temperature - old.temperature))
            val newP = hour.precipProbability
            val oldP = old.precipProbability
            if (newP != null && oldP != null) maxDp = maxOf(maxDp, abs(newP - oldP))
            if (hour.condition != old.condition) conditionChanges++
        }
        val detail = "overlap=$overlap maxDt=${"%.1f".format(maxDt)} maxDp=$maxDp conditions=$conditionChanges " +
            "tailAgeMin=$tailAgeMin"
        // Most of page 1 must be comparable, or "no change" means nothing.
        if (overlap * 2 < page1.size) return Decision(true, "overlap_low $detail")
        val changed = maxDt >= TEMP_TOLERANCE_F ||
            maxDp >= PRECIP_TOLERANCE_POINTS ||
            conditionChanges > MAX_CONDITION_CHANGES
        return Decision(changed, "${if (changed) "changed" else "unchanged"} $detail")
    }
}
