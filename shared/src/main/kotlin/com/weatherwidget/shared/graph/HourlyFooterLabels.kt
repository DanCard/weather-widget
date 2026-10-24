package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.HourLabelFormatter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Which points of an hourly graph get a footer label, and what it says — the temperature,
 * precipitation and cloud graphs on both platforms (plans/261024-shared-hourly-footer-labels.md).
 *
 * - Date mode ([HourlyZoomRules.isDateMode]): one label per local date, on the point nearest local
 *   noon, so partial first/last days still get one.
 * - Otherwise **tidy clock hours**: `localHour % interval == 0` on the hour (user, 2026-10-24).
 *   Android counted from the window's left edge, so a 1 pm start at 4 h labelled 1p 5p 9p where
 *   desktop showed 12p 4p 8p; labels moved on every pan, and a missing forecast hour shifted the rest.
 *   [interval] is a platform input (Android's stage / column rule, desktop's pixel budget) and every
 *   value divides 24.
 * - Nothing else: no always-labelled "now" hour (Android's precip and cloud graphs had one; the
 *   temperature graph and desktop did not; user, 2026-10-24).
 */
object HourlyFooterLabels {
    data class Label(val index: Int, val text: String, val isDate: Boolean)

    /** Labels for [pointTimesMs] (epoch ms, ascending), sorted by index. */
    fun select(pointTimesMs: List<Long>, spanHours: Long, interval: Int, zone: ZoneId): List<Label> {
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
            local.indices
                .filter { local[it].minute == 0 && local[it].hour % step == 0 }
                .map { Label(it, HourLabelFormatter.hourLabel(local[it].hour), isDate = false) }
        }
    }

    /** The times in [pointTimesMs] that [select] labels. */
    fun labelledTimes(pointTimesMs: List<Long>, spanHours: Long, interval: Int, zone: ZoneId): Set<Long> =
        select(pointTimesMs, spanHours, interval, zone).mapTo(HashSet()) { pointTimesMs[it.index] }
}
