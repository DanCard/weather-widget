package com.weatherwidget.data.local

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Entity(tableName = "app_logs")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: String = "DEBUG",
) {
    fun getFormattedTime(): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(log: AppLogEntity)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<AppLogEntity>

    @Query("SELECT * FROM app_logs WHERE tag = :tag ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLogsByTag(tag: String, limit: Int): List<AppLogEntity>

    @Query(
        """
        SELECT * FROM app_logs
        WHERE tag LIKE 'CURR_FETCH%'
            OR tag LIKE 'OBS_CURRENT%'
            OR tag LIKE 'OBS_HOURLY_BACKFILL%'
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getCurrentObservationFetchLogs(limit: Int): List<AppLogEntity>

    @Query("SELECT * FROM app_logs WHERE tag = 'CURRENT_TEMP_STATUS' AND message LIKE 'source=' || :sourceId || '%' ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestCurrentTempStatus(sourceId: String): AppLogEntity?

    @Query(
        """
        SELECT MAX(timestamp) FROM app_logs
        WHERE tag LIKE 'CURR_FETCH%'
            OR tag LIKE 'OBS_CURRENT%'
            OR tag LIKE 'OBS_HOURLY_BACKFILL%'
            OR tag = 'CURRENT_TEMP_STATUS'
        """,
    )
    fun observeLatestCurrentObservationFetchLogAt(): Flow<Long?>

    @Query("SELECT * FROM app_logs WHERE tag IN ('DB_CREATE', 'DB_DESTRUCTIVE_MIGRATION') ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestDatabaseLifecycleEvent(): AppLogEntity?

    @Query("DELETE FROM app_logs WHERE timestamp < :cutoff")
    suspend fun deleteOldLogs(cutoff: Long)

    /**
     * Hard cap on table size: delete all but the newest [keep] rows *outside* [protectedTags]
     * (ordered by the autoincrement primary key, which is monotonic with insert order and already
     * indexed). A backstop for the age-based [deleteOldLogs] — at high write rates the 72h window
     * alone let app_logs reach ~100k rows / 18MB in 3 days, and every click pays an indexed insert
     * into that table.
     *
     * Split from a single whole-table cap on 2026-07-22: routine telemetry writes ~32k rows/day, so
     * one shared budget let it trim the table to 38h even though the age policy says 72h, and the
     * widget push breadcrumbs needed to diagnose a blank widget aged out with it. Giving the two
     * classes separate budgets means telemetry volume can no longer evict diagnostics.
     */
    @Query(
        """
        DELETE FROM app_logs
        WHERE tag NOT IN (:protectedTags)
          AND id NOT IN (
              SELECT id FROM app_logs WHERE tag NOT IN (:protectedTags)
              ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun capUnprotectedToNewest(keep: Int, protectedTags: List<String>)

    /**
     * The same hard cap applied to [protectedTags] alone, so a runaway in a protected tag still
     * cannot grow the table without bound. Sized so the normal 72h of breadcrumbs fits well inside
     * it and only a genuine anomaly is ever trimmed.
     */
    @Query(
        """
        DELETE FROM app_logs
        WHERE tag IN (:protectedTags)
          AND id NOT IN (
              SELECT id FROM app_logs WHERE tag IN (:protectedTags)
              ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun capProtectedToNewest(keep: Int, protectedTags: List<String>)

    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun getCount(): Int

    @Query("DELETE FROM app_logs")
    suspend fun clearAllLogs()
}

/**
 * Crashlytics is auto-initialized by a Firebase ContentProvider only when google-services.json is
 * present. In unit tests — and in any build without that file — there is no default FirebaseApp, so
 * FirebaseCrashlytics.getInstance() throws IllegalStateException ("Default FirebaseApp is not
 * initialized"). Logging must be best-effort and must never throw into its callers, so every
 * Crashlytics access goes through this guard, which no-ops when Firebase is unavailable.
 */
private inline fun crashlytics(block: (FirebaseCrashlytics) -> Unit) {
    try {
        block(FirebaseCrashlytics.getInstance())
    } catch (e: Exception) {
        // No initialized FirebaseApp (tests / no google-services.json). DB + logcat still captured.
    }
}

/** Log to the app_logs DB table, logcat, and Firebase Crashlytics in one call. */
suspend fun AppLogDao.log(tag: String, message: String, level: String = "DEBUG") {
    // VERBOSE is logcat-only and never persisted — matching the shared-module dbLogger boundary
    // (see CurrentTemperatureResolver.resultLogLevel). This lets high-frequency, per-paint/per-poll
    // diagnostics pass "VERBOSE" to stay out of app_logs, so they neither bloat the DB nor add an
    // indexed insert to the click critical path. Only this level is dropped; DEBUG+ still persist.
    if (level != "VERBOSE") {
        try {
            insert(AppLogEntity(tag = tag, message = message, level = level))
        } catch (e: Exception) {
            Log.e("AppLog", "Failed to log to DB: $e")
        }
    }

    // Mirror to Firebase Crashlytics for better context in crash reports.
    // Crashlytics automatically captures ERROR and WARN as custom logs.
    // We only send significant levels to avoid overwhelming the log buffer.
    if (level == "ERROR" || level == "WARN" || level == "INFO") {
        crashlytics { it.log("[$tag] $message") }
    }

    when (level) {
        "ERROR" -> Log.e(tag, message)
        "WARN" -> Log.w(tag, message)
        "INFO" -> Log.i(tag, message)
        "DEBUG" -> Log.d(tag, message)
        "VERBOSE" -> Log.v(tag, message)
        else -> Log.d(tag, message)
    }
}

/** Log a non-fatal exception to both DB logs and Firebase Crashlytics. */
suspend fun AppLogDao.logException(tag: String, message: String, throwable: Throwable) {
    log(tag, "$message: ${throwable.message}", "ERROR")
    crashlytics { it.recordException(throwable) }
}

/** Global log function for standard Log calls to also hit Crashlytics if needed. */
fun log(tag: String, message: String, level: String = "DEBUG") {
    if (level == "ERROR" || level == "WARN" || level == "INFO") {
        crashlytics { it.log("[$tag] $message") }
    }
    when (level) {
        "ERROR" -> Log.e(tag, message)
        "WARN" -> Log.w(tag, message)
        "INFO" -> Log.i(tag, message)
        "DEBUG" -> Log.d(tag, message)
        "VERBOSE" -> Log.v(tag, message)
        else -> Log.d(tag, message)
    }
}

/** Global exception function. */
fun logException(throwable: Throwable) {
    crashlytics { it.recordException(throwable) }
}
