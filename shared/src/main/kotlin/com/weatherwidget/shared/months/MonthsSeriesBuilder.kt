package com.weatherwidget.shared.months

import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.util.DailyDayValueResolver
import java.time.LocalDate

/** One day's plotted high and low (°F) and what kind of value it is. */
data class MonthsDayValue(
    val date: LocalDate,
    val high: Float?,
    val low: Float?,
    val kind: Kind,
) {
    enum class Kind {
        /** Measured actual — drawn solid. */
        ACTUAL,

        /** A past day with no actual, standing in with its frozen forecast — dashed, as on the daily view. */
        PAST_FORECAST,

        /** Today and later — the forecast, dashed. */
        FORECAST,
    }

    val dashed: Boolean get() = kind != Kind.ACTUAL
}

enum class MonthsLine { HIGH, LOW }

/** A run of consecutive days drawn as one line. */
data class MonthsSegment(
    val line: MonthsLine,
    val dashed: Boolean,
    val points: List<Pair<LocalDate, Float>>,
)

/**
 * Builds the months view's per-day values and line segments from what both platforms already load:
 * the daily view's per-date actuals (`DailyActualsAssembler` output for the display source) and the
 * display source's forecast for today on.
 *
 * Raw values, no smoothing, no interpolation: a missing day breaks the line.
 */
object MonthsSeriesBuilder {

    /** First date the months view reads: the whole `daily_history` record. */
    fun historyStart(today: LocalDate): LocalDate = today.minusDays(RetentionPolicy.DAILY_HISTORY_DAYS)

    /**
     * @param pastActuals the display source's daily rows by date (the daily view's own map)
     * @param forecasts the display source's forecast (high, low) by date; only today and later are read
     */
    fun values(
        pastActuals: Map<LocalDate, DailyHistory>,
        forecasts: Map<LocalDate, Pair<Float?, Float?>>,
        today: LocalDate,
    ): List<MonthsDayValue> {
        val past = pastActuals.filterKeys { it.isBefore(today) }.mapNotNull { (date, row) ->
            // Same rule as the past-day column's centre bar: actual, else the frozen forecast.
            val resolved = DailyDayValueResolver.resolvePastLineValues(
                actualHigh = row.computedHighTemp,
                actualLow = row.computedLowTemp,
                forecastHigh = row.forecastHighTemp,
                forecastLow = row.forecastLowTemp,
            )
            if (resolved.solidHigh == null && resolved.solidLow == null) return@mapNotNull null
            MonthsDayValue(
                date = date,
                high = resolved.solidHigh,
                low = resolved.solidLow,
                kind = if (resolved.solidIsForecastFallback) MonthsDayValue.Kind.PAST_FORECAST else MonthsDayValue.Kind.ACTUAL,
            )
        }
        val future = forecasts.filterKeys { !it.isBefore(today) }.mapNotNull { (date, pair) ->
            if (pair.first == null && pair.second == null) return@mapNotNull null
            MonthsDayValue(date, pair.first, pair.second, MonthsDayValue.Kind.FORECAST)
        }
        return (past + future).sortedBy { it.date }
    }

    /** First and last day with a value, or null when there is nothing to draw. */
    fun bounds(values: List<MonthsDayValue>): ClosedRange<LocalDate>? =
        if (values.isEmpty()) null else values.first().date..values.last().date

    /**
     * Splits one line into drawable runs. A missing day ends a run. Where the style changes between
     * two adjacent days (actual → forecast), the new run starts from the previous day's point so the
     * line stays continuous.
     */
    fun segments(values: List<MonthsDayValue>, line: MonthsLine): List<MonthsSegment> {
        val result = mutableListOf<MonthsSegment>()
        var current: MutableList<Pair<LocalDate, Float>>? = null
        var currentDashed = false
        var previous: Pair<LocalDate, Float>? = null
        for (day in values.sortedBy { it.date }) {
            val value = (if (line == MonthsLine.HIGH) day.high else day.low)
            if (value == null) {
                current?.let { result += MonthsSegment(line, currentDashed, it) }
                current = null
                previous = null
                continue
            }
            val point = day.date to value
            val adjacent = previous?.first?.plusDays(1) == day.date
            when {
                current != null && adjacent && currentDashed == day.dashed -> current += point
                current != null && adjacent -> {
                    result += MonthsSegment(line, currentDashed, current)
                    current = mutableListOf(previous!!, point)
                    currentDashed = day.dashed
                }
                else -> {
                    current?.let { result += MonthsSegment(line, currentDashed, it) }
                    current = mutableListOf(point)
                    currentDashed = day.dashed
                }
            }
            previous = point
        }
        current?.let { result += MonthsSegment(line, currentDashed, it) }
        return result
    }
}
