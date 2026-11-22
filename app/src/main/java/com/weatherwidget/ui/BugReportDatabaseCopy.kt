package com.weatherwidget.ui

import android.os.Build
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import java.io.File

private const val TAG = "BugReportDatabaseCopy"

/**
 * A self-contained copy of the Room database for a bug-report zip. API keys are not in it: they
 * live in SharedPreferences (`WeatherSourcePreferences`), which is never attached.
 */
object BugReportDatabaseCopy {
    /**
     * Writes the copy to [target] (replaced if present) and returns it, or null on failure.
     * API 30+ uses `VACUUM INTO` (consistent under concurrent writes, no WAL needed); older
     * SQLite lacks it, so the WAL is checkpointed into the main file and that file is copied.
     */
    fun write(database: WeatherDatabase, target: File): File? = try {
        target.parentFile?.mkdirs()
        target.delete()
        val db = database.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            db.execSQL("VACUUM INTO ?", arrayOf<Any>(target.absolutePath))
        } else {
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            File(db.path ?: error("database has no path")).copyTo(target, overwrite = true)
        }
        target
    } catch (e: Exception) {
        Log.e(TAG, "Could not copy database for bug report", e)
        target.delete()
        null
    }
}
