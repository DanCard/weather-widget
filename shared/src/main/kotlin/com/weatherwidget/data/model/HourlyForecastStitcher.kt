package com.weatherwidget.data.model

/**
 * Merges current hourly rows with historical forecast snapshots.
 *
 * Current rows win. Historical rows are only used to backfill missing nullable fields for the same
 * hour, which keeps the canonical pipeline intact while still letting the UI render the past 72
 * hours.
 */
object HourlyForecastStitcher {
    fun stitch(current: List<HourlyForecast>, history: List<HourlyForecast>): List<HourlyForecast> {
        if (current.isEmpty() && history.isEmpty()) return emptyList()

        val historyByTime = history.groupBy { it.dateTime }
        val byTime = LinkedHashMap<Long, HourlyForecast>()
        
        // Initial populate from history (prefer primary source snapshots if multiple exist)
        historyByTime.forEach { (time, rows) ->
            // Try to find a row from the primary source first (anything NOT "Generic"), else Generic, else anything
            val bestRow = rows.find { it.source != "Generic" } ?: rows.first()
            byTime[time] = bestRow
        }
        
        for (row in current) {
            val historicalRows = historyByTime[row.dateTime] ?: emptyList()
            
            // Repair missing fields using any historical row that has them.
            // This allows the "Generic" backfill to repair NWS gaps even if an NWS snapshot exists.
            var repaired = row
            if (repaired.cloudCover == null) {
                repaired = repaired.copy(cloudCover = historicalRows.firstNotNullOfOrNull { it.cloudCover })
            }
            if (repaired.precipProbability == null) {
                repaired = repaired.copy(precipProbability = historicalRows.firstNotNullOfOrNull { it.precipProbability })
            }
            if (repaired.precipAmountMm == null) {
                repaired = repaired.copy(precipAmountMm = historicalRows.firstNotNullOfOrNull { it.precipAmountMm })
            }
            
            byTime[row.dateTime] = repaired
        }
        return byTime.values.sortedBy { it.dateTime }
    }
}
