package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyExtremeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(extremes: List<DailyExtremeEntity>)

    @Query(
        """
        SELECT * FROM daily_extremes
        WHERE date >= :startDate
          AND date <= :endDate
          AND ${LocationMatch.ROOM_WHERE}
        ORDER BY date ASC
        """,
    )
    suspend fun getExtremesInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<DailyExtremeEntity>

    @Query("DELETE FROM daily_extremes WHERE updatedAt < :cutoffMs")
    suspend fun deleteOldExtremes(cutoffMs: Long)
}
