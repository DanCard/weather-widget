package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.util.PartialForecastDays

internal object DailyTodayResolver {
    /**
     * Today's daily row, by the rule desktop shares ([PartialForecastDays.todayRow]). [batchRow]
     * is today's entry in `weatherByDate` (newest fetch, display source), or null when that
     * fetch has none (Silurian's evening batches start at tomorrow). [snapshots] are every stored
     * row for today. Only the display source's rows compete, at [batchRow]'s site when there is
     * one; GENERIC_GAP filler never stands in for today.
     */
    fun resolveTodayRow(
        batchRow: ForecastEntity?,
        snapshots: List<ForecastEntity>,
        displaySourceId: String,
    ): ForecastEntity? =
        PartialForecastDays.todayRow(
            batchRow,
            snapshots.filter {
                it.source == displaySourceId &&
                    (batchRow == null || LocationMatch.sameSite(batchRow.locationLat, batchRow.locationLon, it.locationLat, it.locationLon))
            },
            { it.highTemp },
            { it.lowTemp },
            { it.fetchedAt },
        )
}
