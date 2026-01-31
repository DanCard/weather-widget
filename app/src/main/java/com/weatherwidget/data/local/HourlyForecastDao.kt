package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface HourlyForecastDao {

    @Query("""
        SELECT * FROM hourly_forecasts
        WHERE locationLat = :lat
        AND locationLon = :lon
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        ORDER BY dateTime ASC
    """)
    suspend fun getHourlyForecasts(
        startDateTime: String,
        endDateTime: String,
        lat: Double,
        lon: Double
    ): List<HourlyForecastEntity>

    @Query("""
        SELECT * FROM hourly_forecasts
        WHERE locationLat = :lat
        AND locationLon = :lon
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source = :source
        ORDER BY dateTime ASC
    """)
    suspend fun getHourlyForecastsBySource(
        startDateTime: String,
        endDateTime: String,
        lat: Double,
        lon: Double,
        source: String
    ): List<HourlyForecastEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forecasts: List<HourlyForecastEntity>)

    @Query("DELETE FROM hourly_forecasts WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldForecasts(cutoffTime: Long)
}
