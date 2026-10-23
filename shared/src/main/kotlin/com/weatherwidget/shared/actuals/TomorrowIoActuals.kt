package com.weatherwidget.shared.actuals

/** Source-of-truth provenance for Tomorrow.io's five-minute analysis actuals. */
object TomorrowIoActuals {
    const val FIVE_MINUTE_HISTORY_STATION_ID = "TOMORROW_IO_5M_HISTORY"
    const val FIVE_MINUTE_HISTORY_STATION_NAME = "Tmrw: 5-minute history"

    // Legacy product ids are retained only for targeted cleanup and regression tests. Neither is
    // accepted by the actual-temperature pipeline.
    const val RECENT_HISTORY_STATION_ID = "TOMORROW_IO_RECENT_HISTORY"
    const val RECENT_HISTORY_STATION_NAME = "Tmrw: Recent History"
    const val REALTIME_STATION_ID = "TOMORROW_IO_REALTIME"
    const val REALTIME_STATION_NAME = "Tmrw: Realtime"
    // This id is in-memory only; persisted rows retain five-minute provenance.
    const val MERGED_SERIES_STATION_ID = "Tmrw"
    const val MERGED_SERIES_STATION_NAME = "Tmrw: 5-minute history"

    fun isAllowedStation(stationId: String): Boolean = stationId == FIVE_MINUTE_HISTORY_STATION_ID

    /** Tag of [fetchLine]: one Tomorrow.io five-minute actuals write, both platforms. */
    const val FETCH_TAG = "TMRW_5M_FETCH"

    /** A reading's identity and exact (quantized) site: what an insert would replace. */
    data class ReadingKey(val stationId: String, val timestamp: Long, val api: String, val lat: Double, val lon: Double)

    /**
     * How many [incoming] readings overwrite a stored one: same station, time and api at the same
     * site. [stored] is the proximity-box read over the incoming range; rows at other sites in the
     * box never count.
     */
    fun replacements(incoming: List<ReadingKey>, stored: List<ReadingKey>): Int {
        val existing = stored.toSet()
        return incoming.count { it in existing }
    }

    fun fetchLine(windowHours: Int, rows: Int, earliestMs: Long?, latestMs: Long?, replacements: Int): String =
        "windowHours=$windowHours rows=$rows earliest=$earliestMs latest=$latestMs replacements=$replacements"
}
