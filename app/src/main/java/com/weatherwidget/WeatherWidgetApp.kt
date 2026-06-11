package com.weatherwidget

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.AndroidLogSink
import com.weatherwidget.util.CrashReporter
import com.weatherwidget.widget.OpportunisticUpdateJobService
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@HiltAndroidApp
class WeatherWidgetApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Lazy: resolving AppLogDao eagerly here would open the database during Application.onCreate,
    // before instrumented/Robolectric tests install their in-memory test DB. Lazy defers the DB open
    // until the first crash, which is also when we actually need it.
    @Inject
    lateinit var appLogDao: Lazy<AppLogDao>

    override fun onCreate() {
        super.onCreate()
        // Route shared-module logging to logcat before anything else runs, so even startup-time
        // diagnostics from :shared (e.g. TemperatureLabelResolver's label-placement breadcrumbs) are
        // visible on-device. Without this they sink into java.util.logging and never reach logcat.
        com.weatherwidget.shared.util.Log.install(AndroidLogSink)
        installCrashLogger()
        processStartElapsedRealtime = SystemClock.elapsedRealtime()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(this)
        }
    }

    /**
     * Last-resort crash capture: persists every uncaught exception to the app_logs table (visible in
     * AppLogsActivity and shareable from there) before the process dies, then delegates to the
     * previously-registered handler. That previous handler is Crashlytics' once wired (Firebase
     * auto-installs it via a ContentProvider before onCreate), or the platform default otherwise — so
     * chaining preserves both auto-upload and the normal system crash dialog. Must never itself throw.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val message = CrashReporter.formatCrashMessage(thread.name, throwable)
                // The process is dying, so persist synchronously with a hard timeout. If the write
                // hangs or fails, fall back to logcat — never let crash handling worsen the crash.
                runBlocking {
                    withTimeoutOrNull(CRASH_PERSIST_TIMEOUT_MS) {
                        appLogDao.get().log(CrashReporter.CRASH_TAG, message, "ERROR")
                    }
                }
            } catch (t: Throwable) {
                Log.e(CrashReporter.CRASH_TAG, "Failed to persist crash", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()

    companion object {
        private const val CRASH_PERSIST_TIMEOUT_MS = 2000L

        @Volatile
        private var processStartElapsedRealtime: Long = 0L

        fun processAgeMs(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long {
            val start = processStartElapsedRealtime
            return if (start > 0L) (nowElapsedRealtime - start).coerceAtLeast(0L) else Long.MAX_VALUE
        }
    }
}
