package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface HourlyForecastHistoryDao {
    /**
     * Hourly forecast as predicted in a specific [snapshotBucket]. Lets a future feature reconstruct
     * "what the forecast said for hour H as of bucket B" (e.g. yesterday's prediction for today).
     */
    @Query(
        """
        SELECT * FROM hourly_forecast_history
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        AND source = :source
        AND snapshotBucket = :snapshotBucket
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
        snapshotBucket: Long,
    ): List<HourlyForecastHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(history: List<HourlyForecastHistoryEntity>)

    @Query("DELETE FROM hourly_forecast_history WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)
}
