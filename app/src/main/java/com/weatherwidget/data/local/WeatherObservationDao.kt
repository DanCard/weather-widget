package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeatherObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(observations: List<WeatherObservationEntity>)

    @Query("SELECT * FROM weather_observations WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getRecentObservations(sinceMs: Long): List<WeatherObservationEntity>

    @Query("DELETE FROM weather_observations WHERE timestamp < :cutoffMs")
    suspend fun deleteOldObservations(cutoffMs: Long)

    @Query("SELECT * FROM weather_observations WHERE stationId = :stationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForStation(stationId: String): WeatherObservationEntity?
}
