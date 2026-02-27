package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(observations: List<ObservationEntity>)

    @Query("SELECT * FROM observations WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity>

    @Query(
        """
        SELECT * FROM observations
        WHERE timestamp >= :startTs
          AND timestamp < :endTs
          AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
          AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        ORDER BY timestamp ASC
    """,
    )
    suspend fun getObservationsInRange(
        startTs: Long,
        endTs: Long,
        lat: Double,
        lon: Double
    ): List<ObservationEntity>

    @Query("DELETE FROM observations WHERE timestamp < :cutoffMs")
    suspend fun deleteOldObservations(cutoffMs: Long)

    @Query("SELECT * FROM observations WHERE stationId = :stationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForStation(stationId: String): ObservationEntity?
}
