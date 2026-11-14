package com.weatherwidget.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.weatherwidget.data.model.HindcastGuard

/**
 * Today's forecast low and high are predictions only until a fixed local cutoff — the low until
 * [LOW_CUTOFF] (06:00), the high until [HIGH_CUTOFF] (16:00). After it, a fetch's value is a
 * hindcast, and it may replace the stored one only when the prior forecast is missing (user,
 * 2026-11-13: "don't allow hindcasts unless prior data is missing"):
 *
 * 1. the value from the last fetch at or before the cutoff, if it is at most
 *    [HindcastGuard.maxPriorAgeMs] older than the cutoff;
 * 2. otherwise (no prior, or a stale one) the value from the **first** fetch after the cutoff, so
 *    later hindcasts do not move it;
 * 3. if this fetch is that first one, its own value (the stale prior when it sends none).
 *
 * The age limit is what the earlier freezes lacked: the 06:00 low freeze (removed 2026-11-06) held a
 * 53 h-old 57° low against a fresh 46.6° hourly. With [HindcastGuard] off (0 h), every fetch writes.
 * [LOW_CUTOFF] is also the D−1 anchor for [PriorDayForecast] and the History of Forecasts trim.
 * See plans/261113-no-hindcasts-unless-prior-missing.md.
 *
 * Wired from the shared daily writer ([DailyForecastWrite]) used by both platforms. Display reads
 * whatever the DB holds; settling writes is enough.
 */
object SameDayExtremeCutoff {
    val LOW_CUTOFF: LocalTime = LocalTime.of(6, 0)
    val HIGH_CUTOFF: LocalTime = LocalTime.of(16, 0)

    /** One stored row's value for the extreme being settled, with its fetch stamps. */
    data class Prior(val value: Float?, val fetchedAt: Long, val lastConfirmedAt: Long)

    enum class Reason { BEFORE_CUTOFF, GUARD_OFF, FRESH_PRIOR, FIRST_AFTER_CUTOFF, THIS_FETCH_FIRST }

    /**
     * The value to store. [held] = the sent value was not used (it belongs in the hindcast column).
     * [priorAgeMs]: how long before the cutoff the newest prior was last confirmed; null when none.
     */
    data class Settled(val value: Float?, val held: Boolean, val reason: Reason, val priorAgeMs: Long?)

    fun cutoffMs(date: LocalDate, cutoff: LocalTime, zone: ZoneId): Long =
        date.atTime(cutoff).atZone(zone).toInstant().toEpochMilli()

    /**
     * Settles today's low or high at [cutoffMs] from the day's stored rows [priors] and this fetch's
     * [sent] value. A row restamped after the cutoff (a later fetch sent identical values) counts as
     * confirmed at the cutoff: that fetch agreed with it, so keeping it changes nothing.
     */
    fun settle(
        priors: List<Prior>,
        sent: Float?,
        cutoffMs: Long,
        nowMs: Long,
        maxPriorAgeMs: Long = HindcastGuard.maxPriorAgeMs,
    ): Settled {
        if (nowMs < cutoffMs) return Settled(sent, held = false, Reason.BEFORE_CUTOFF, null)
        if (maxPriorAgeMs <= 0) return Settled(sent, held = false, Reason.GUARD_OFF, null)
        val withValue = priors.filter { it.value != null }
        val pre = withValue.filter { it.fetchedAt <= cutoffMs }
            .maxByOrNull { maxOf(it.fetchedAt, minOf(it.lastConfirmedAt, cutoffMs)) }
        val priorAge = pre?.let { cutoffMs - maxOf(it.fetchedAt, minOf(it.lastConfirmedAt, cutoffMs)) }
        if (pre != null && priorAge!! <= maxPriorAgeMs) {
            return Settled(pre.value, held = true, Reason.FRESH_PRIOR, priorAge)
        }
        val firstAfter = withValue.filter { it.fetchedAt > cutoffMs }.minByOrNull { it.fetchedAt }
        if (firstAfter != null) return Settled(firstAfter.value, held = true, Reason.FIRST_AFTER_CUTOFF, priorAge)
        return Settled(sent ?: pre?.value, held = false, Reason.THIS_FETCH_FIRST, priorAge)
    }

    /**
     * Today's low when a fetch sends none: the stored one, if fetched within
     * [PartialForecastDays.COMPLETE_REPLACEMENT_MAX_AGE_MS]. The night that ends this morning began
     * yesterday, so a source may simply not report it any more — Google's 07:00→07:00 day files it
     * under yesterday (`GoogleWeatherApi.lowsFiledUnderTheirMorning`), and NWS drops "Tonight" in the
     * evening. Writing null made the reader swap in an older complete row, high included; keeping the
     * low leaves this fetch's high standing. Null for any other day.
     * `plans/261010-google-daily-low-filed-under-the-morning-it-ends.md`
     */
    fun keptTodayLow(isToday: Boolean, priorLow: Float?, priorFetchedAt: Long?, nowMs: Long): Float? =
        priorLow.takeIf {
            isToday && priorFetchedAt != null && nowMs - priorFetchedAt <= PartialForecastDays.COMPLETE_REPLACEMENT_MAX_AGE_MS
        }
}
