package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface HourlyForecastDao {
    @Query(
        """
        SELECT * FROM hourly_forecasts
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
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
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source = :source
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forecasts: List<HourlyForecastEntity>)

    @Query("DELETE FROM hourly_forecasts WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldForecasts(cutoffTime: Long)

    @Query("DELETE FROM hourly_forecasts WHERE fetchedAt < :cutoffTime AND source = :source")
    suspend fun deleteOldForecastsBySource(cutoffTime: Long, source: String)
}
