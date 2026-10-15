package com.weatherwidget.shared.months

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.util.DailyDayValueResolver
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How far back one source's months line reaches at the current site. */
data class SourceDepth(val sourceId: String, val first: LocalDate, val days: Int)

/**
 * "NWS has history back to Jun 2" — whether another source has a much deeper record than the one
 * the months view shows. Each source keeps its own `daily_history` rows, so depth follows when that
 * source started being used, not when the app was installed (Pixel 7 Pro, 2026-10-15: Google from
 * Oct 4, NWS from Jun 2 at the same site). See
 * plans/261015-months-view-suggests-source-with-more-history.md.
 */
object MonthsSourceDepth {
    /** The other source must start at least this much earlier to be worth a line. */
    const val MIN_EXTRA_DAYS = 30L

    /** …and have at least this many days, so a few rows left at an old site never win. */
    const val MIN_DAYS = 14

    /**
     * One depth per source in [rows] (the DAO's site-box read, every source). A day counts when the
     * months line would draw it: the same rule as [MonthsSeriesBuilder.values] (actual, else the
     * frozen forecast), before [today]. Same-day fragments count once.
     */
    fun depths(rows: List<DailyHistory>, today: LocalDate): List<SourceDepth> =
        rows
            .filter { it.toLocalDate().isBefore(today) && drawable(it) }
            .groupBy { it.source }
            .map { (source, sourceRows) ->
                val dates = sourceRows.mapTo(HashSet()) { it.toLocalDate() }
                SourceDepth(source, dates.min(), dates.size)
            }

    private fun drawable(row: DailyHistory): Boolean {
        val values = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = row.computedHighTemp,
            actualLow = row.computedLowTemp,
            forecastHigh = row.forecastHighTemp,
            forecastLow = row.forecastLowTemp,
        )
        return values.solidHigh != null || values.solidLow != null
    }

    /**
     * The source to suggest instead of [currentId], or null. Only [visibleIds] are candidates (in the
     * user's order, which breaks the last tie); the most days wins, then the earlier start.
     */
    fun suggestion(currentId: String, depths: List<SourceDepth>, visibleIds: List<String>): SourceDepth? {
        val currentFirst = depths.find { it.sourceId == currentId }?.first
        return depths
            .filter { it.sourceId != currentId && it.sourceId in visibleIds && it.days >= MIN_DAYS }
            .filter { currentFirst == null || ChronoUnit.DAYS.between(it.first, currentFirst) >= MIN_EXTRA_DAYS }
            .sortedWith(
                compareByDescending<SourceDepth> { it.days }
                    .thenBy { it.first }
                    .thenBy { visibleIds.indexOf(it.sourceId) },
            )
            .firstOrNull()
    }
}
