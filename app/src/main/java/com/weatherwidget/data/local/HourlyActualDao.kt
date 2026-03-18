package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface HourlyActualDao {
    @Query(
        """
        SELECT * FROM hourly_actuals
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        AND source = :source
        ORDER BY dateTime ASC
    """,
    )
    suspend fun getActualsInRange(
        startDateTime: String,
        endDateTime: String,
        source: String,
        lat: Double,
        lon: Double,
    ): List<HourlyActualEntity>

    @Query(
        """
        SELECT * FROM hourly_actuals
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        AND dateTime >= :startDateTime
        AND dateTime <= :endDateTime
        ORDER BY source ASC, dateTime ASC
    """,
    )
    suspend fun getActualsInRangeAllSources(
        startDateTime: String,
        endDateTime: String,
        lat: Double,
        lon: Double,
    ): List<HourlyActualEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(actuals: List<HourlyActualEntity>)

    @Query("DELETE FROM hourly_actuals WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldActuals(cutoffTime: Long)
}
