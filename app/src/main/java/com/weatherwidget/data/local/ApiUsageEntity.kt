package com.weatherwidget.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Requests per local day, source and endpoint (`ApiUsageClassifier` in `:shared`). Desktop keeps
 * the same table (`DesktopWeatherDatabase.API_USAGE_STATS_DDL`). [errorCount] counts HTTP >= 400
 * and failed sends; [quotaRefusedCount] the 429s among them.
 */
@Entity(
    tableName = "api_usage_stats",
    primaryKeys = ["date", "apiSource", "endpoint"]
)
data class ApiUsageEntity(
    val date: Long,
    val apiSource: String,
    @ColumnInfo(defaultValue = "")
    val endpoint: String = "",
    val callCount: Int = 1,
    @ColumnInfo(defaultValue = "0")
    val errorCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val quotaRefusedCount: Int = 0,
)

@Dao
interface ApiUsageDao {
    @Query(
        "UPDATE api_usage_stats SET callCount = callCount + 1, errorCount = errorCount + :error, " +
            "quotaRefusedCount = quotaRefusedCount + :quotaRefused " +
            "WHERE date = :date AND apiSource = :apiSource AND endpoint = :endpoint"
    )
    suspend fun incrementUsage(date: Long, apiSource: String, endpoint: String, error: Int, quotaRefused: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ApiUsageEntity): Long

    @Transaction
    suspend fun logCall(
        date: Long,
        apiSource: String,
        endpoint: String = "",
        isError: Boolean = false,
        isQuotaRefusal: Boolean = false,
    ) {
        val error = if (isError) 1 else 0
        val refused = if (isQuotaRefusal) 1 else 0
        val updated = incrementUsage(date, apiSource, endpoint, error, refused)
        if (updated == 0) {
            insert(ApiUsageEntity(date, apiSource, endpoint, 1, error, refused))
        }
    }

    @Query("SELECT * FROM api_usage_stats WHERE date = :date AND apiSource = :apiSource AND endpoint = :endpoint")
    suspend fun getUsage(date: Long, apiSource: String, endpoint: String = ""): ApiUsageEntity?

    @Query("SELECT * FROM api_usage_stats WHERE date = :date AND apiSource = :apiSource ORDER BY endpoint")
    suspend fun getUsageByEndpoint(date: Long, apiSource: String): List<ApiUsageEntity>

    /** Retention: `date` is the day's epoch ms. See RetentionPolicy. */
    @Query("DELETE FROM api_usage_stats WHERE date < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT SUM(callCount) FROM api_usage_stats WHERE apiSource = :apiSource")
    suspend fun getTotalUsage(apiSource: String): Int?
}
