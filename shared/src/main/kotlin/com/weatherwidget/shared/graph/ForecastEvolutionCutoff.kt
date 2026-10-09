package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.EvolutionPoint
import com.weatherwidget.shared.util.SameDayExtremeCutoff
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The "History of Forecasts" graphs show forecasts only: a high fetched after the high was reached
 * (or a low after the low) is a hindcast and is removed (user, 2026-10-09: "remove them entirely").
 * Same rule as the past-day overlay ([com.weatherwidget.shared.actuals.ForecastOverlaySettle]).
 *
 * The fixed [SameDayExtremeCutoff] times always apply as well: after them the writer stores a copy
 * of the last pre-cutoff value, which would otherwise be drawn again at the later fetch time.
 * See plans/261009-forecast-history-drops-hindcast-points.md.
 */
object ForecastEvolutionCutoff {
    /** One stored forecast row for the target day, as both platforms' DAOs return it. */
    data class Row(
        val dateOfPredictionMs: Long,
        val fetchedAt: Long,
        val highTemp: Float?,
        val lowTemp: Float?,
        val sourceId: String,
    )

    /**
     * The graph points for [targetDate]: rows predicted on or before it, then [apply]. The one
     * builder Android (`ForecastHistoryActivity`) and desktop (`ForecastHistoryWindow`) share.
     */
    fun points(
        rows: List<Row>,
        targetDate: LocalDate,
        highReachedAt: Long?,
        lowReachedAt: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EvolutionPoint> = apply(
        points = rows.mapNotNull { row ->
            val forecastDate = LocalDate.ofEpochDay(row.dateOfPredictionMs / MS_PER_DAY)
            val daysAhead = ChronoUnit.DAYS.between(forecastDate, targetDate).toInt()
            if (daysAhead < 0) {
                null
            } else {
                EvolutionPoint(
                    forecastDate = forecastDate.toString(),
                    fetchedAt = row.fetchedAt,
                    daysAhead = daysAhead,
                    highTemp = row.highTemp,
                    lowTemp = row.lowTemp,
                    source = WeatherSource.fromId(row.sourceId),
                )
            }
        },
        targetDate = targetDate,
        highReachedAt = highReachedAt,
        lowReachedAt = lowReachedAt,
        zone = zone,
    )

    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    /**
     * [points] with each side nulled when fetched after its cutoff; points left with neither side
     * are dropped. [highReachedAt]/[lowReachedAt] are `daily_history.computedHighAt/LowAt` for the
     * same source and day, null when unknown (today, or no actuals).
     */
    fun apply(
        points: List<EvolutionPoint>,
        targetDate: LocalDate,
        highReachedAt: Long?,
        lowReachedAt: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EvolutionPoint> {
        val fixedHigh = targetDate.atTime(SameDayExtremeCutoff.HIGH_CUTOFF).atZone(zone).toInstant().toEpochMilli()
        val fixedLow = targetDate.atTime(SameDayExtremeCutoff.LOW_CUTOFF).atZone(zone).toInstant().toEpochMilli()

        fun isForecast(fetchedAt: Long, fixedMs: Long, reachedAt: Long?) =
            fetchedAt < fixedMs && (reachedAt == null || fetchedAt <= reachedAt)

        return points.mapNotNull { p ->
            val high = p.highTemp.takeIf { isForecast(p.fetchedAt, fixedHigh, highReachedAt) }
            val low = p.lowTemp.takeIf { isForecast(p.fetchedAt, fixedLow, lowReachedAt) }
            if (high == null && low == null) null else p.copy(highTemp = high, lowTemp = low)
        }
    }
}
