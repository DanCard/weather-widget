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
}
