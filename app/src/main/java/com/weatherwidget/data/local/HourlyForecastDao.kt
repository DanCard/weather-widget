package com.weatherwidget.data.local

import androidx.room.*

/**
 * The newest fetch's row per hour, source and site of the one hourly table, and its writes. For every
 * fetch's rows, see [HourlyForecastHistoryDao].
 */
@Dao
interface HourlyForecastDao {
    @Query(
        """
        SELECT * FROM hourly_forecasts
        WHERE ${LocationMatch.ROOM_WHERE}
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source != '${com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID}'
        GROUP BY dateTime, source, locationLat, locationLon
        HAVING fetchedAt = MAX(fetchedAt)
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getHourlyForecasts(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
    ): List<HourlyForecastEntity>

    @Query(
        """
        SELECT * FROM hourly_forecasts
        WHERE ${LocationMatch.ROOM_WHERE}
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source = :source
        GROUP BY dateTime, source, locationLat, locationLon
        HAVING fetchedAt = MAX(fetchedAt)
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getHourlyForecastsBySource(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<HourlyForecastEntity>

    /**
     * Hourly rows restricted to [sources] — the display source of each installed widget plus
     * `GENERIC_GAP`, mirroring `ForecastDao.getForecastsInRangeForSources` on the daily side.
     *
     * Prefer this over [getHourlyForecasts] on any render path. The unfiltered query returns every
     * source the app has ever fetched and every consumer then filters in memory to the display
     * source: on a Samsung Fold that was 4928 rows materialized to use 841 (see
     * plans/260803-daily-load-window-right-sizing.md).
     */
    @Query(
        """
        SELECT * FROM hourly_forecasts
        WHERE ${LocationMatch.ROOM_WHERE}
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source IN (:sources)
        GROUP BY dateTime, source, locationLat, locationLon
        HAVING fetchedAt = MAX(fetchedAt)
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getHourlyForecastsForSources(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<HourlyForecastEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forecasts: List<HourlyForecastEntity>)
}
