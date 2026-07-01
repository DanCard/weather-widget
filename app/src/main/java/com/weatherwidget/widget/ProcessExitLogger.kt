package com.weatherwidget.widget

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records *why the previous widget process(es) died* into the durable app_logs table.
 *
 * Motivation: when the home-screen widget appears "dead" (frozen on stale data, taps do nothing),
 * the usual cause is that the app's process was no longer running when the tap arrived. But nothing
 * on-device recorded why it died — logcat's crash buffer only holds recent Java crashes and rotates
 * within hours, so a death from a day ago is unrecoverable. The platform *does* retain the cause via
 * [ActivityManager.getHistoricalProcessExitReasons] (API 30+): low-memory kill, crash, ANR,
 * user/force-stop, signal, excessive-resource, freezer, etc. This reads that history on startup and
 * mirrors it into app_logs (which is retained for a month and shareable from AppLogsActivity), so the
 * next time the widget looks dead we can query the DB and see the actual reason — e.g. distinguish a
 * low-memory reap (power-independent) from a battery/standby force-stop from a crash.
 *
 * De-duplicated across process lifetimes via a SharedPreferences cursor (the newest already-logged
 * exit timestamp), so each death is logged exactly once no matter how often this runs.
 */
object ProcessExitLogger {
    const val TAG = "PROC_EXIT"
    private const val PREFS_NAME = "process_exit_logger"
    private const val KEY_LAST_LOGGED_TS = "last_logged_exit_ts"
    private const val MAX_EXITS = 30

    private val loggedThisProcess = AtomicBoolean(false)
    private val timestampFormat =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /**
     * Logs any process-exit records newer than the stored cursor, at most once per process lifetime.
     * Safe to call from any on-device DB path (e.g. the widget worker); a no-op below API 30 and after
     * the first call in this process. Never throws.
     */
    suspend fun logRecentExitsOnce(context: Context, appLogDao: AppLogDao) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!loggedThisProcess.compareAndSet(false, true)) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, /* pid= */ 0, MAX_EXITS)
            if (exits.isEmpty()) return

            val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
            val lastLoggedTs = prefs.getLong(KEY_LAST_LOGGED_TS, 0L)
            var newestSeen = lastLoggedTs

            // getHistoricalProcessExitReasons returns most-recent-first.
            for (info in exits) {
                if (info.timestamp <= lastLoggedTs) break
                newestSeen = maxOf(newestSeen, info.timestamp)
                appLogDao.log(TAG, formatExit(info), levelFor(info))
            }

            if (newestSeen > lastLoggedTs) {
                prefs.edit().putLong(KEY_LAST_LOGGED_TS, newestSeen).apply()
            }
        } catch (t: Throwable) {
            // Diagnostics must never destabilize the caller.
            Log.w(TAG, "Failed to read historical process exit reasons", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun formatExit(info: ApplicationExitInfo): String =
        formatExit(
            reason = info.reason,
            timestampMs = info.timestamp,
            importance = info.importance,
            status = info.status,
            pssKb = info.pss,
            rssKb = info.rss,
            processName = info.processName,
            description = info.description,
        )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun levelFor(info: ApplicationExitInfo): String = levelForReason(info.reason)

    /**
     * Human-readable one-line summary of a single process death, for the app_logs message column.
     * Takes primitives (not [ApplicationExitInfo], which cannot be constructed in a plain unit test)
     * so the formatting is exercisable without a device — see ProcessExitLoggerTest.
     */
    @VisibleForTesting
    internal fun formatExit(
        reason: Int,
        timestampMs: Long,
        importance: Int,
        status: Int,
        pssKb: Long,
        rssKb: Long,
        processName: String?,
        description: String?,
    ): String =
        "reason=${reasonName(reason)} " +
            "at=${timestampFormat.format(Instant.ofEpochMilli(timestampMs))} " +
            "importance=${importanceName(importance)} " +
            "status=$status " +
            "pss=${pssKb}KB rss=${rssKb}KB " +
            "process=$processName " +
            "desc=\"${description ?: ""}\""

    /**
     * ERROR for crashes, WARN for the deaths that plausibly explain a "dead widget" (low-memory reap,
     * ANR, excessive resource use, forced stop), INFO for benign/expected exits — so a DB query can
     * filter to the interesting causes.
     */
    @VisibleForTesting
    internal fun levelForReason(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            -> "ERROR"
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            -> "WARN"
            else -> "INFO"
        }

    @VisibleForTesting
    internal fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            else -> "UNKNOWN($reason)"
        }

    // Constants are compile-time-inlined ints (all pre-R), so this needs no API gate.
    private fun importanceName(importance: Int): String =
        when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "IMP($importance)"
        }
}
