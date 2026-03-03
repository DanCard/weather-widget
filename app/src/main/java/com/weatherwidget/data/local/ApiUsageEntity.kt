package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "api_usage_stats",
    primaryKeys = ["date", "apiSource"]
)
data class ApiUsageEntity(
    val date: String,
    val apiSource: String,
    val callCount: Int = 1
)

@Dao
interface ApiUsageDao {
    @Query("UPDATE api_usage_stats SET callCount = callCount + 1 WHERE date = :date AND apiSource = :apiSource")
    suspend fun incrementUsage(date: String, apiSource: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ApiUsageEntity): Long

    @Transaction
    suspend fun logCall(date: String, apiSource: String) {
        val updated = incrementUsage(date, apiSource)
        if (updated == 0) {
            insert(ApiUsageEntity(date, apiSource, 1))
        }
    }

    @Query("SELECT * FROM api_usage_stats WHERE date = :date AND apiSource = :apiSource")
    suspend fun getUsage(date: String, apiSource: String): ApiUsageEntity?

    @Query("SELECT SUM(callCount) FROM api_usage_stats WHERE apiSource = :apiSource")
    suspend fun getTotalUsage(apiSource: String): Int?
}
