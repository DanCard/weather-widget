package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.HourLabelFormatter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Which points of an hourly graph get a footer label, and what it says — the temperature,
 * precipitation and cloud graphs on both platforms (plans/261024-shared-hourly-footer-labels.md,
 * plans/261107-hourly-footer-labels-from-window-start.md).
 *
 * - Date mode ([HourlyZoomRules.isDateMode]): one label per local date, on the point nearest local
 *   noon, so partial first/last days still get one.
 * - Otherwise **from the window start**: the first whole hour at or after [windowStartMs] is
 *   labelled, then every [interval] hours after it (user, 2026-11-07: "Start with the correct time
 *   label. In this case 10 am. Then do a cadence after that."). Tidy clock hours (`hour % interval`)
 *   left the left corner unlabelled for up to interval-1 hours.
 *   - The anchor is the window start, never the first point: the precip and cloud graphs pass only
 *     hours with a forecast, and a missing first hour must not shift every label after it.
 *   - The cadence counts elapsed hours, so spacing stays even across a DST change.
 *   - [interval] is a platform input (Android's stage / column rule, desktop's pixel budget).
 * - Nothing else: no always-labelled "now" hour (user, 2026-10-24).
 */
object HourlyFooterLabels {
    data class Label(val index: Int, val text: String, val isDate: Boolean)

    private const val HOUR_MS = 3_600_000L

    /** Labels for [pointTimesMs] (epoch ms, ascending) in a window starting at [windowStartMs], sorted by index. */
    fun select(
        pointTimesMs: List<Long>,
        windowStartMs: Long,
        spanHours: Long,
        interval: Int,
        zone: ZoneId,
    ): List<Label> {
        if (pointTimesMs.isEmpty()) return emptyList()
        val local = pointTimesMs.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
        return if (HourlyZoomRules.isDateMode(spanHours)) {
            local.indices
                .groupBy { local[it].toLocalDate() }
                .values
                .map { indices -> indices.minBy { abs(local[it].hour * 60 + local[it].minute - 12 * 60) } }
                .sorted()
                .map { Label(it, HourLabelFormatter.dateLabel(local[it].toLocalDate()), isDate = true) }
        } else {
            val step = interval.coerceAtLeast(1)
            val anchorMs = firstWholeHourAtOrAfter(windowStartMs, zone)
            local.indices
                .filter { i ->
                    val sinceAnchor = pointTimesMs[i] - anchorMs
                    local[i].minute == 0 && local[i].second == 0 && sinceAnchor >= 0 &&
                        sinceAnchor % HOUR_MS == 0L && (sinceAnchor / HOUR_MS) % step == 0L
                }
                .map { Label(it, HourLabelFormatter.hourLabel(local[it].hour), isDate = false) }
        }
    }

    /** The times in [pointTimesMs] that [select] labels. */
    fun labelledTimes(
        pointTimesMs: List<Long>,
        windowStartMs: Long,
        spanHours: Long,
        interval: Int,
        zone: ZoneId,
    ): Set<Long> =
        select(pointTimesMs, windowStartMs, spanHours, interval, zone).mapTo(HashSet()) { pointTimesMs[it.index] }

    /** The first local whole hour at or after [ms] (a 10:20 window start anchors at 11:00). */
    internal fun firstWholeHourAtOrAfter(ms: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val floor = t.withMinute(0).withSecond(0).withNano(0)
        return if (floor.toInstant().toEpochMilli() == ms) ms else floor.plusHours(1).toInstant().toEpochMilli()
    }
}
