package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Shared climate-normals compute, used by both the Android widget (Room-cached) and the
 * desktop app (JDBC-cached). Normals = a rough seasonal average high/low per calendar day,
 * the future-day fallback when no real forecast exists.
 *
 * Pipeline (each platform supplies its own fetch + cache around these pure steps):
 *   archive daily temps --[monthlyMeans]--> 12 monthly means --[cache]-->
 *   --[expandMonthlyToDaily]--> per-day Map<MonthDay, high/low>.
 */
object ClimateNormals {

    /**
     * The fetch window: a rolling 20-year span ending at the most recent complete year.
     * Rolling (vs a fixed reference period) so it includes the latest years and reflects the
     * current climate, advancing automatically each year. Returns ("YYYY-01-01", "YYYY-12-31").
     */
    fun rollingWindow(today: LocalDate = LocalDate.now()): Pair<String, String> {
        val endYear = today.year - 1
        val startYear = endYear - 19
        return "$startYear-01-01" to "$endYear-12-31"
    }

    /** Cache key: lat/lon rounded to 0.1° (~7mi), one source of truth so both platforms agree. */
    fun locationKey(latitude: Double, longitude: Double): String =
        "${(latitude * 10).roundToInt() / 10.0}_${(longitude * 10).roundToInt() / 10.0}"

    /**
     * Groups archive daily highs/lows by month and averages each (ignoring NaN), rounded to one
     * decimal. Returns month(1..12) -> mean high and month -> mean low (a month is absent if it had
     * no usable data).
     */
    fun monthlyMeans(daily: List<DailyForecast>): Pair<Map<Int, Float>, Map<Int, Float>> {
        val byMonth = daily.groupBy { LocalDate.parse(it.date).monthValue }
        val monthlyHigh = mutableMapOf<Int, Float>()
        val monthlyLow = mutableMapOf<Int, Float>()
        for ((month, rows) in byMonth) {
            val highs = rows.map { it.highTemp }.filter { !it.isNaN() }
            val lows = rows.map { it.lowTemp }.filter { !it.isNaN() }
            if (highs.isNotEmpty()) monthlyHigh[month] = roundToTenth(highs.average())
            if (lows.isNotEmpty()) monthlyLow[month] = roundToTenth(lows.average())
        }
        return monthlyHigh to monthlyLow
    }

    private fun roundToTenth(value: Double): Float = (value * 10).roundToInt() / 10f

    /**
     * Expands 12 monthly means (keyed by month 1..12) into a value for every calendar day by
     * linear interpolation. Each month's mean is anchored at the 15th; days between anchors are
     * interpolated, wrapping across the Dec↔Jan boundary. Iterates a leap year so Feb 29 is covered.
     */
    fun expandMonthlyToDaily(
        monthlyHigh: Map<Int, Float>,
        monthlyLow: Map<Int, Float>,
    ): Map<MonthDay, Pair<Float, Float>> {
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) return emptyMap()

        val baseYear = 2020 // leap year so Feb 29 is covered
        val avgHigh = monthlyHigh.values.average().toFloat()
        val avgLow = monthlyLow.values.average().toFloat()

        data class Anchor(val date: LocalDate, val high: Float, val low: Float)
        fun anchorFor(year: Int, month: Int) =
            Anchor(LocalDate.of(year, month, 15), monthlyHigh[month] ?: avgHigh, monthlyLow[month] ?: avgLow)

        // Wrap-around neighbors ensure every day of baseYear sits between two anchors.
        val anchors = buildList {
            add(anchorFor(baseYear - 1, 12))
            for (m in 1..12) add(anchorFor(baseYear, m))
            add(anchorFor(baseYear + 1, 1))
        }.sortedBy { it.date }

        val result = mutableMapOf<MonthDay, Pair<Float, Float>>()
        var date = LocalDate.of(baseYear, 1, 1)
        val end = LocalDate.of(baseYear, 12, 31)
        while (!date.isAfter(end)) {
            val prev = anchors.last { !it.date.isAfter(date) }
            val next = anchors.first { !it.date.isBefore(date) }
            if (prev.date == next.date) {
                result[MonthDay.from(date)] = prev.high to prev.low
            } else {
                val span = ChronoUnit.DAYS.between(prev.date, next.date).toFloat()
                val pos = ChronoUnit.DAYS.between(prev.date, date).toFloat() / span
                result[MonthDay.from(date)] =
                    (prev.high + (next.high - prev.high) * pos) to (prev.low + (next.low - prev.low) * pos)
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** One climate-normal-derived fallback day, for a date not covered by a real forecast. */
    data class GapDay(val date: LocalDate, val highTemp: Float, val lowTemp: Float)

    /**
     * One [GapDay] per date in `[today, today+horizonDays]` not already in [coveredDates] and having
     * a normal for its [MonthDay] in [normalsByMonthDay]. Empty normals (nothing cached yet) yields an
     * empty list — callers should treat that as "no fallback available", not an error.
     */
    fun fillGaps(
        coveredDates: Set<LocalDate>,
        normalsByMonthDay: Map<MonthDay, Pair<Float, Float>>,
        today: LocalDate,
        horizonDays: Long,
    ): List<GapDay> {
        if (normalsByMonthDay.isEmpty()) return emptyList()

        val gaps = mutableListOf<GapDay>()
        var date = today
        val end = today.plusDays(horizonDays)
        while (!date.isAfter(end)) {
            if (date !in coveredDates) {
                normalsByMonthDay[MonthDay.from(date)]?.let { (high, low) ->
                    gaps.add(GapDay(date, high, low))
                }
            }
            date = date.plusDays(1)
        }
        return gaps
    }
}
