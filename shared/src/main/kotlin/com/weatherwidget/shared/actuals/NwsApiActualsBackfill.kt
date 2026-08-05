package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.remote.OpenMeteoApi
import java.time.LocalDate

/**
 * Backfills NWS apiHighTemp/apiLowTemp using Open-Meteo's ERA5 archive endpoint.
 * Shared between Android and desktop — each platform handles its own DAO queries/upserts.
 */
object NwsApiActualsBackfill {

    /**
     * Fetches ERA5 reanalysis daily highs/lows for the given dates and returns them keyed by
     * epoch-day-millis. Only dates that the archive actually covers are included.
     *
     * @param openMeteoApi API client for the archive endpoint
     * @param latitude user location lat
     * @param longitude user location lon
     * @param epochDayMillis list of UTC-midnight epoch millis for dates needing backfill
     * @return map of epochDayMillis → (highTempF, lowTempF)
     */
    suspend fun backfill(
        fetchArchive: suspend (startDate: String, endDate: String) -> List<DailyForecast>,
        latitude: Double,
        longitude: Double,
        epochDayMillis: List<Long>,
    ): Map<Long, Pair<Float, Float>> {
        if (epochDayMillis.isEmpty()) return emptyMap()

        val dates = epochDayMillis.map { LocalDate.ofEpochDay(it / 86_400_000L) }
        val archiveStart = dates.min().toString()
        val archiveEnd = dates.max().toString()

        val archiveDaily = try {
            fetchArchive(archiveStart, archiveEnd)
        } catch (e: Exception) {
            return emptyMap()
        }

        val archiveByDate = archiveDaily.associateBy { it.date }
        val result = mutableMapOf<Long, Pair<Float, Float>>()
        for (epochMs in epochDayMillis) {
            val dateStr = LocalDate.ofEpochDay(epochMs / 86_400_000L).toString()
            val row = archiveByDate[dateStr] ?: continue
            result[epochMs] = row.highTemp to row.lowTemp
        }
        return result
    }
}
