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
     * How long before its cutoff a fetch may have been made and still count as "yesterday's
     * forecast". Between [STALE_SLACK_HOURS] and this the pick stands but draws dashed; older is no
     * pick at all. Unbounded, a site first fetched a week earlier froze that week-old 90/74 as
     * Oct 9's prior forecast (Pixel, 2026-10-09).
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
     * Only rows fetched within [MAX_PICK_AGE_HOURS] before a cutoff compete for it.
     *
     * @param fallbackToEarliest when no usable row qualifies for a cutoff, take the earliest usable
     *   row fetched after it instead. Today's live column does (the user chose showing a late row
     *   over hiding it, 2026-09-25); the frozen history does not — a "yesterday's forecast" fetched
     *   after its cutoff is not one. Rows too old to qualify never stand in.
     */
    fun <T> select(
        candidates: List<T>,
        date: LocalDate,
        zone: ZoneId,
        fetchedAt: (T) -> Long,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fallbackToEarliest: Boolean,
    ): Pick<T> {
        val usable = candidates.filter {
            val h = high(it)
            val l = low(it)
            !(h != null && l != null && h == l)
        }
        fun side(cutoffMs: Long, valueOf: (T) -> Float?): T? {
            val withValue = usable.filter { valueOf(it) != null }
            return withValue.filter { fetchedAt(it) < cutoffMs && !isTooOld(fetchedAt(it), cutoffMs) }
                .maxByOrNull(fetchedAt)
                ?: if (fallbackToEarliest) {
                    withValue.filter { fetchedAt(it) >= cutoffMs }.minByOrNull(fetchedAt)
                } else {
                    null
                }
        }
        return Pick(
            highRow = side(highCutoffMs(date, zone), high),
            lowRow = side(lowCutoffMs(date, zone), low),
        )
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
    ): Past? {
        val pick = if (frozenHigh != null && frozenLow != null) {
            null
        } else {
            select(candidates, date, zone, fetchedAt, high, low, fallbackToEarliest = false)
        }
        val late by lazy { select(candidates, date, zone, fetchedAt, high, low, fallbackToEarliest = true) }
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
     * @param lastConfirmedAtMillis Android `batchFetchedAt` (dedup re-stamps it), desktop `fetchedAt`.
     */
    fun isStale(lastConfirmedAtMillis: Long, cutoffMs: Long): Boolean =
        cutoffMs - lastConfirmedAtMillis > STALE_SLACK_HOURS * 3_600_000L
}
