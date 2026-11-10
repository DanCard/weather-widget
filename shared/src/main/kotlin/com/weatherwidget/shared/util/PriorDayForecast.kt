package com.weatherwidget.shared.util

import java.time.LocalDate
import java.time.ZoneId

/**
 * "Yesterday's forecast" for a day — the left bar of the daily triple bar, on today's column and on
 * every past column. Pure Kotlin, shared by the Android widget, the desktop app and the
 * `daily_history` freeze ([com.weatherwidget.shared.actuals.DailyHistoryMaintenance.planPriorForecasts]).
 *
 * For target date D (user's rule, 2026-10-05), each value is taken from a different anchor:
 * - **low**: the newest forecast fetched before 06:00 on D−1 — 24h before [SameDayExtremeCutoff.LOW_CUTOFF];
 * - **high**: the newest forecast fetched before 16:00 on D−1 — 24h before [SameDayExtremeCutoff.HIGH_CUTOFF].
 *
 * The two may come from different fetches; that is the rule (as in `ForecastOverlaySettle`). The
 * anchors are local wall-clock times, so a DST day still anchors at 06:00/16:00 rather than drifting
 * an hour. Unlike the old "24h before now" rule, the pick does not slide while the day goes on.
 *
 * See plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md.
 */
object PriorDayForecast {

    /**
     * How long before its cutoff a picked forecast may have been last confirmed before the bar is
     * drawn dashed: the same 24h of slack the old `now − 48h` rule gave a `now − 24h` pick.
     */
    const val STALE_SLACK_HOURS = 24L

    /**
     * How long before its cutoff a forecast may have been last confirmed and still count as
     * "yesterday's forecast". Between [STALE_SLACK_HOURS] and this the pick stands but draws dashed;
     * older never qualifies — it is never frozen into history (a site first fetched a week earlier
     * froze that week-old 90/74 as Oct 9's prior forecast, Pixel 2026-10-09) — but the live bar
     * shows it as a dashed stand-in rather than nothing ([select], user 2026-09-25 and 2026-10-23).
     */
    const val MAX_PICK_AGE_HOURS = 48L

    /** True when a fetch made at [fetchedAtMs] is too old to stand for the anchor at [cutoffMs]. */
    fun isTooOld(fetchedAtMs: Long, cutoffMs: Long): Boolean =
        cutoffMs - fetchedAtMs > MAX_PICK_AGE_HOURS * 3_600_000L

    fun lowCutoffMs(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.minusDays(1).atTime(SameDayExtremeCutoff.LOW_CUTOFF).atZone(zone).toInstant().toEpochMilli()

    fun highCutoffMs(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.minusDays(1).atTime(SameDayExtremeCutoff.HIGH_CUTOFF).atZone(zone).toInstant().toEpochMilli()

    /** The row each side was taken from; either may be null. */
    data class Pick<T>(val highRow: T?, val lowRow: T?) {
        val isEmpty: Boolean get() = highRow == null && lowRow == null
    }

    /**
     * Picks each side independently from [candidates] (already filtered by the caller to one date,
     * source and site, and to real forecasts — no climate normals or gap fill).
     *
     * A row is usable for a side when that side is present and the row is not collapsed
     * (high == low, a degenerate row that would draw a zero-height bar).
     *
     * A row qualifies for a cutoff when it was first seen before it ([fetchedAt]) and last confirmed
     * no more than [MAX_PICK_AGE_HOURS] before it ([lastConfirmedAt]; a confirmation after the cutoff
     * means it was still current then). The newest first-seen qualifier wins — the version current
     * at the cutoff. Android stores an unchanged forecast once and re-stamps its `lastConfirmedAt`, so
     * aging it by first sighting dropped a stable forecast after two days (2026-10-23).
     *
     * @param fallbackToEarliest when no usable row qualifies for a cutoff, take the earliest usable
     *   row fetched after it, else the newest one first seen before it however old — both drawn as
     *   stand-ins. Today's live column does (the user chose showing an older or later forecast over
     *   hiding the bar, 2026-09-25 / 2026-10-23); the frozen history does not — a "yesterday's
     *   forecast" fetched after its cutoff, or long before it, is not one.
     */
    fun <T> select(
        candidates: List<T>,
        date: LocalDate,
        zone: ZoneId,
        fetchedAt: (T) -> Long,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fallbackToEarliest: Boolean,
        lastConfirmedAt: (T) -> Long = fetchedAt,
    ): Pick<T> {
        val usable = usable(candidates, high, low)
        return Pick(
            highRow = side(usable, highCutoffMs(date, zone), fetchedAt, high, lastConfirmedAt, fallbackToEarliest),
            lowRow = side(usable, lowCutoffMs(date, zone), fetchedAt, low, lastConfirmedAt, fallbackToEarliest),
        )
    }

    /**
     * How far back a future day's left bar looks: the forecast as it stood this long before now
     * (plans/261110-future-days-prior-forecast-bar.md).
     */
    const val FUTURE_LOOKBACK_HOURS = 24L

    /** The instant a future day's left bar reads its forecast "as of": [FUTURE_LOOKBACK_HOURS] before [nowMs]. */
    fun futureCutoffMs(nowMs: Long): Long = nowMs - FUTURE_LOOKBACK_HOURS * 3_600_000L

    /**
     * A future day's left bar ("forecast 24 h ago", opt-in setting): for each side, the forecast
     * current at [futureCutoffMs] — the newest row first seen before it, last confirmed no more than
     * [MAX_PICK_AGE_HOURS] before it. Today's fixed anchors (06:00 / 16:00 on D−1) are still ahead
     * for days after tomorrow, so the anchor slides with now; at midnight tomorrow becomes today and
     * switches to [select]'s anchors.
     *
     * No fallback: a stand-in would repeat the forecast the column already draws. The last horizon
     * day (first fetched under a day ago) therefore has no bar. Same [usable] filter as [select].
     */
    fun <T> selectAsOf(
        candidates: List<T>,
        nowMs: Long,
        fetchedAt: (T) -> Long,
        high: (T) -> Float?,
        low: (T) -> Float?,
        lastConfirmedAt: (T) -> Long = fetchedAt,
    ): Pick<T> {
        val usable = usable(candidates, high, low)
        val cutoffMs = futureCutoffMs(nowMs)
        return Pick(
            highRow = side(usable, cutoffMs, fetchedAt, high, lastConfirmedAt, fallbackToEarliest = false),
            lowRow = side(usable, cutoffMs, fetchedAt, low, lastConfirmedAt, fallbackToEarliest = false),
        )
    }

    /** A future pick is drawn dashed when either side was last confirmed over [STALE_SLACK_HOURS] before [futureCutoffMs]. */
    fun <T> isFuturePickStale(pick: Pick<T>, nowMs: Long, lastConfirmedAt: (T) -> Long): Boolean {
        val cutoffMs = futureCutoffMs(nowMs)
        return listOfNotNull(pick.highRow, pick.lowRow).any { isStale(lastConfirmedAt(it), cutoffMs) }
    }

    /** Rows with a usable shape: not collapsed (high == low draws a zero-height bar). */
    private fun <T> usable(candidates: List<T>, high: (T) -> Float?, low: (T) -> Float?): List<T> =
        candidates.filter {
            val h = high(it)
            val l = low(it)
            !(h != null && l != null && h == l)
        }

    /** One side's pick at [cutoffMs] — see [select] for the qualifying and fallback rules. */
    private fun <T> side(
        usable: List<T>,
        cutoffMs: Long,
        fetchedAt: (T) -> Long,
        valueOf: (T) -> Float?,
        lastConfirmedAt: (T) -> Long,
        fallbackToEarliest: Boolean,
    ): T? {
        val withValue = usable.filter { valueOf(it) != null }
        val firstSeenBefore = withValue.filter { fetchedAt(it) < cutoffMs }
        return firstSeenBefore.filter { !isTooOld(minOf(lastConfirmedAt(it), cutoffMs), cutoffMs) }
            .maxByOrNull(fetchedAt)
            ?: if (fallbackToEarliest) {
                withValue.filter { fetchedAt(it) >= cutoffMs }.minByOrNull(fetchedAt)
                    ?: firstSeenBefore.maxByOrNull(fetchedAt)
            } else {
                null
            }
    }

    /** A past day's left bar. [isFallback]: a side was fetched after its anchor — drawn dashed. */
    data class Past(val high: Float, val low: Float, val isFallback: Boolean)

    /**
     * A past day's left bar as rendered: each side's frozen `daily_history.priorForecast*` value
     * when present, else picked live from [candidates] by [select] with no fallback (the frozen
     * history's own rule). The live pick covers the time before the freeze has run — the first
     * paints after an upgrade, and every paint until the next full sync, which on battery can be
     * hours away. The frozen value outlives the `forecasts` table's 30-day retention.
     *
     * When neither finds a side, the earliest row carrying it stands in (today's own fallback), then
     * the earliest post-cutoff [hindcastHigh]/[hindcastLow]; either makes the bar a dashed fallback
     * (user's call, 2026-10-07 — a source added mid-day otherwise shows no left bar for two days).
     * Null unless both ends are known (half a bar is never drawn).
     */
    fun <T> resolvePast(
        frozenHigh: Float?,
        frozenLow: Float?,
        candidates: List<T>,
        date: LocalDate,
        zone: ZoneId,
        fetchedAt: (T) -> Long,
        high: (T) -> Float?,
        low: (T) -> Float?,
        hindcastHigh: (T) -> Float? = { null },
        hindcastLow: (T) -> Float? = { null },
        lastConfirmedAt: (T) -> Long = fetchedAt,
    ): Past? {
        val pick = if (frozenHigh != null && frozenLow != null) {
            null
        } else {
            select(candidates, date, zone, fetchedAt, high, low, fallbackToEarliest = false, lastConfirmedAt)
        }
        val late by lazy { select(candidates, date, zone, fetchedAt, high, low, fallbackToEarliest = true, lastConfirmedAt) }
        fun side(frozen: Float?, picked: T?, valueOf: (T) -> Float?, lateRow: () -> T?, hindcast: (T) -> Float?): Pair<Float, Boolean>? =
            frozen?.let { it to false }
                ?: picked?.let(valueOf)?.let { it to false }
                ?: lateRow()?.let(valueOf)?.let { it to true }
                ?: candidates.filter { hindcast(it) != null }.minByOrNull(fetchedAt)?.let { hindcast(it)!! to true }
        val h = side(frozenHigh, pick?.highRow, high, { late.highRow }, hindcastHigh) ?: return null
        val l = side(frozenLow, pick?.lowRow, low, { late.lowRow }, hindcastLow) ?: return null
        return Past(h.first, l.first, isFallback = h.second || l.second)
    }

    /**
     * True when a picked row was last confirmed more than [STALE_SLACK_HOURS] before its cutoff —
     * the device fetched nothing in the day before the anchor. Drawn dashed, never hidden
     * ([com.weatherwidget.shared.graph.StandInBarStyle]). A fallback row fetched after its cutoff is
     * not stale.
     *
     * @param lastConfirmedAtMillis When the row was last confirmed (`lastConfirmedAt`).
     */
    fun isStale(lastConfirmedAtMillis: Long, cutoffMs: Long): Boolean =
        cutoffMs - lastConfirmedAtMillis > STALE_SLACK_HOURS * 3_600_000L

    /** Today's pick is a stand-in (drawn dashed) when either side was last confirmed too long before its anchor. */
    fun <T> isPickStale(pick: Pick<T>, date: java.time.LocalDate, zone: java.time.ZoneId, lastConfirmedAt: (T) -> Long): Boolean =
        pick.highRow?.let { isStale(lastConfirmedAt(it), highCutoffMs(date, zone)) } == true ||
            pick.lowRow?.let { isStale(lastConfirmedAt(it), lowCutoffMs(date, zone)) } == true
}
