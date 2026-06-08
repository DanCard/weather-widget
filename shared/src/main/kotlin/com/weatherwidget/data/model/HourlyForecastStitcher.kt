package com.weatherwidget.data.model

/**
 * Merges current hourly rows with historical forecast snapshots.
 *
 * Current rows win. Historical rows are only used when the current table is missing that hour,
 * which keeps the canonical pipeline intact while still letting the UI render the past 72 hours.
 */
object HourlyForecastStitcher {
    fun stitch(current: List<HourlyForecast>, history: List<HourlyForecast>): List<HourlyForecast> {
        if (current.isEmpty() && history.isEmpty()) return emptyList()

        val byTime = LinkedHashMap<Long, HourlyForecast>()
        for (row in history) {
            byTime[row.dateTime] = row
        }
        for (row in current) {
            byTime[row.dateTime] = row
        }
        return byTime.values.sortedBy { it.dateTime }
    }
}
