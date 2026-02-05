package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Entity(tableName = "app_logs")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: String = "INFO"
) {
    fun getFormattedTime(): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(log: AppLogEntity)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<AppLogEntity>

    @Query("SELECT * FROM app_logs WHERE tag = :tag ORDER BY timestamp DESC")
    suspend fun getLogsByTag(tag: String): List<AppLogEntity>

    @Query("DELETE FROM app_logs WHERE timestamp < :cutoff")
    suspend fun deleteOldLogs(cutoff: Long)

    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun getCount(): Int
}
