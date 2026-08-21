package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface HourlyForecastHistoryDao {
    /**
     * Hourly forecast as predicted in a specific [timestampToGroupPredictions]. Lets a future feature reconstruct
     * "what the forecast said for hour H as of bucket B" (e.g. yesterday's prediction for today).
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source = :source
        AND timestampToGroupPredictions = :timestampToGroupPredictions
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getHistoryForBucket(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        source: String,
        timestampToGroupPredictions: Long,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Hourly forecasts for hours in [[startDateTime], [endDateTime]) captured within the snapshot
     * bucket window [[bucketStart], [bucketEnd]) — used to reconstruct "what the prior-day forecast
     * said" for day/night rain accuracy. Ordered so the latest bucket per hour can be taken first.
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source = :source
        AND dateTime >= :startDateTime AND dateTime < :endDateTime
        AND timestampToGroupPredictions >= :bucketStart AND timestampToGroupPredictions < :bucketEnd
        ORDER BY dateTime ASC, timestampToGroupPredictions DESC
    """,
    )
    suspend fun getHistoryInRangeForBucketWindow(
        startDateTime: Long,
        endDateTime: Long,
        bucketStart: Long,
        bucketEnd: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<HourlyForecastHistoryEntity>

    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE ${LocationMatch.ROOM_WHERE}
        AND dateTime >= :startDateTime AND dateTime < :endDateTime
        AND timestampToGroupPredictions >= :bucketStart AND timestampToGroupPredictions < :bucketEnd
        ORDER BY dateTime ASC, timestampToGroupPredictions DESC
    """,
    )
    suspend fun getHistoryInRangeForBucketWindowAllSources(
        startDateTime: Long,
        endDateTime: Long,
        bucketStart: Long,
        bucketEnd: Long,
        lat: Double,
        lon: Double,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Bucket-window history restricted to [sources]. Multi-source (not the single-source
     * [getHistoryInRangeForBucketWindow]) because several widgets can display different sources at
     * once, so the render paths need the union of their display sources plus `GENERIC_GAP`.
     *
     * This is the hot one: `hourly_forecast_history` holds every snapshot of every hour, so the
     * AllSources variant returned 33,473 rows over a 72h-back/168h-forward window on a Samsung Fold
     * where the display source accounted for 7,597. Prefer this on any render path.
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source IN (:sources)
        AND dateTime >= :startDateTime AND dateTime < :endDateTime
        AND timestampToGroupPredictions >= :bucketStart AND timestampToGroupPredictions < :bucketEnd
        ORDER BY dateTime ASC, timestampToGroupPredictions DESC
    """,
    )
    suspend fun getHistoryInRangeForBucketWindowForSources(
        startDateTime: Long,
        endDateTime: Long,
        bucketStart: Long,
        bucketEnd: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Every snapshot of every source in the range — no bucket window. Used by the frozen-rain-chance
     * repair, which must reconstruct what the live hourly table held at an arbitrary past instant and
     * therefore needs the raw `fetchedAt`-stamped rows, not one prediction bucket.
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE ${LocationMatch.ROOM_WHERE}
        AND dateTime >= :startDateTime AND dateTime < :endDateTime
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getHistoryInRangeAllSnapshots(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Day-ago cloud predictions for the window, as top-of-hour epoch ms -> percent. Backs the cloud
     * graph's frozen forecast curve.
     *
     * Scoped to [com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID], which is never a
     * display source, so these rows cannot reach any forecast path that asks for a real source.
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE source = :priorSource
          AND dateTime >= :startDateTime
          AND dateTime < :endDateTime
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY dateTime ASC, locationLat ASC, locationLon ASC
    """,
    )
    suspend fun getPriorDayCloudCandidates(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        priorSource: String = com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Same-site collapse before the map is built: the coarse box can gather a jitter fragment or a
     * neighbouring town, and two rows for one hour would otherwise resolve by map-insertion order.
     */
    suspend fun getPriorDayCloudForecast(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
    ): Map<Long, Int> = getSyntheticCloudSeries(
        startDateTime, endDateTime, lat, lon,
        com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID,
    )

    /**
     * The settled low-cloud actuals for the window, filed by
     * [com.weatherwidget.data.repository.HourlyForecastStore] under
     * [com.weatherwidget.shared.graph.RetroCloudActual.SOURCE_ID]. Same shape and same site-collapse
     * as the frozen forecast, so the two maps can be zipped hour-for-hour.
     */
    suspend fun getRetroCloudActuals(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
    ): Map<Long, Int> = getSyntheticCloudSeries(
        startDateTime, endDateTime, lat, lon,
        com.weatherwidget.shared.graph.RetroCloudActual.SOURCE_ID,
    )

    private suspend fun getSyntheticCloudSeries(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): Map<Long, Int> =
        getPriorDayCloudCandidates(startDateTime, endDateTime, lat, lon, source)
            .filter { LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon) }
            .mapNotNull { row -> row.cloudCover?.let { row.dateTime to it } }
            .toMap()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(history: List<HourlyForecastHistoryEntity>)

    @Query("DELETE FROM hourly_forecast_history WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)
}
