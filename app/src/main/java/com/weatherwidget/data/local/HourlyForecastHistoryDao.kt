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

    private suspend fun getSyntheticCloudSeries(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): Map<Long, Int> =
        getPriorDayCloudCandidates(startDateTime, endDateTime, lat, lon, source)
            .filter { LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon) }
            // Total-preferred, like every other cloud read. The fallback still finds the rows
            // written while the previous-runs variable was the LOW layer, which carry their value
            // on cloudCoverLow; nothing needs migrating.
            .mapNotNull { row ->
                com.weatherwidget.shared.util.VisibleCloudCover.of(
                    total = row.cloudCover, low = row.cloudCoverLow,
                    mid = row.cloudCoverMid, high = row.cloudCoverHigh,
                )?.let { row.dateTime to it }
            }
            .toMap()

    /**
     * Candidate band snapshots for [getPriorDayBandForecast].
     *
     * Scoped to the REAL source id, not
     * [com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID]: Open-Meteo's Previous Runs
     * API serves no band data at all (see
     * [com.weatherwidget.shared.graph.PriorDayBandForecast]), so the bands' frozen forecast comes
     * from our own hourly snapshots instead.
     *
     * The prediction-bucket range is bounded here rather than in Kotlin so a 30-hour window does
     * not drag every stored version of every hour across the Binder.
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE source = :source
          AND dateTime >= :startDateTime
          AND dateTime < :endDateTime
          AND timestampToGroupPredictions >= :minBucket
          AND timestampToGroupPredictions <= :maxBucket
          AND (cloudCoverMid IS NOT NULL OR cloudCoverHigh IS NOT NULL)
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY dateTime ASC, timestampToGroupPredictions ASC, locationLat ASC, locationLon ASC
    """,
    )
    suspend fun getPriorDayBandCandidates(
        startDateTime: Long,
        endDateTime: Long,
        minBucket: Long,
        maxBucket: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<HourlyForecastHistoryEntity>

    /**
     * Same-site collapse before the reduction, for the same reason [getPriorDayCloudForecast] does
     * it: the coarse box can gather a jitter fragment or a neighbouring town.
     */
    suspend fun getPriorDayBandForecast(
        startDateTime: Long,
        endDateTime: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): Map<Long, com.weatherwidget.shared.graph.CloudBands> =
        com.weatherwidget.shared.graph.PriorDayBandForecast.select(
            getPriorDayBandCandidates(
                startDateTime = startDateTime,
                endDateTime = endDateTime,
                minBucket = startDateTime - com.weatherwidget.shared.graph.PriorDayBandForecast.MAX_LEAD_MS,
                maxBucket = endDateTime - com.weatherwidget.shared.graph.PriorDayBandForecast.LEAD_MS,
                lat = lat,
                lon = lon,
                source = source,
            )
                .filter { LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon) }
                .map { row ->
                    com.weatherwidget.shared.graph.PriorDayBandForecast.BandSnapshot(
                        hourMs = row.dateTime,
                        bucketMs = row.timestampToGroupPredictions,
                        bands = com.weatherwidget.shared.graph.CloudBands(
                            mid = row.cloudCoverMid,
                            high = row.cloudCoverHigh,
                        ),
                    )
                },
        )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(history: List<HourlyForecastHistoryEntity>)

    @Query("DELETE FROM hourly_forecast_history WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)
}
