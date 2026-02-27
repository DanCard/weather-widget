package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CurrentTempDao {
    @Query(
        "SELECT * FROM current_temp WHERE date = :date AND source = :source AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1 ORDER BY observedAt DESC LIMIT 1",
    )
    suspend fun getCurrentTemp(
        date: String,
        source: String,
        lat: Double,
        lon: Double,
    ): CurrentTempEntity?

    @Query(
        "SELECT * FROM current_temp WHERE date = :date AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1",
    )
    suspend fun getCurrentTemps(
        date: String,
        lat: Double,
        lon: Double,
    ): List<CurrentTempEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CurrentTempEntity)

    @Query("DELETE FROM current_temp WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldData(cutoffTime: Long)

    @Query("DELETE FROM current_temp WHERE fetchedAt < :cutoffTime AND source = :source")
    suspend fun deleteOldDataBySource(cutoffTime: Long, source: String)
}
