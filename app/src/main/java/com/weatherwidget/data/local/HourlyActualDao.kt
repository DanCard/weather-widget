package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HourlyActualDao {
    /** Frozen: an existing hour is never rewritten. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<HourlyActualEntity>)

    @Query(
        """
        SELECT * FROM hourly_actuals
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source = :source
        AND hourMs >= :startMs
        AND hourMs < :endMs
        ORDER BY hourMs ASC
    """,
    )
    suspend fun getInRange(startMs: Long, endMs: Long, lat: Double, lon: Double, source: String): List<HourlyActualEntity>

    @Query("SELECT MAX(hourMs) FROM hourly_actuals")
    suspend fun latestHour(): Long?

    @Query("DELETE FROM hourly_actuals WHERE hourMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
