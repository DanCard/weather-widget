package com.weatherwidget

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.AndroidLogSink
import com.weatherwidget.util.CrashReporter
import com.weatherwidget.widget.LegacyDefaultLocationMigration
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
        // Cold-start trace anchor. This line's logcat timestamp is process birth (wall-clock); the
        // first-trigger/first-paint markers below report their age relative to it, so a slow "load"
        // can be split into "OS started the process late" (large gap before this line vs. when the
        // widget was looked at / the triggering broadcast was enqueued) vs. "init+render was slow"
        // (large first_paint ageMs). See COLD_START_TRACE logs.
        Log.i(COLD_START_TAG, "process onCreate pid=${android.os.Process.myPid()}")
        // Before any path can read a location. SharedPreferences only — deliberately no database
        // access here (see the appLogDao comment above); the worker emits the app_logs row later via
        // LegacyDefaultLocationMigration.consumePendingReport.
        runLegacyDefaultLocationMigration()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(this)
        }
    }

    /**
     * Erases the retired Google-HQ placeholder coordinates from an upgrading install. Must never
     * prevent startup: a failure here leaves the old coordinates in place (today's behaviour), while
     * throwing would take the whole app down.
     */
    private fun runLegacyDefaultLocationMigration() {
        try {
            val outcome = LegacyDefaultLocationMigration.runIfNeeded(this)
            if (!outcome.alreadyRun) {
                Log.i(
                    TAG,
                    "LOCATION_MIGRATION cleared=${outcome.clearedCount} " +
                        "active=${outcome.clearedActiveLocation} widgets=${outcome.clearedWidgetIds}",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy default-location migration failed", e)
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
        Thread.setDefaultUncaughtExceptionHandler(buildCrashHandler({ appLogDao.get() }, previous))
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()

    companion object {
        private const val TAG = "WeatherWidgetApp"
        private const val CRASH_PERSIST_TIMEOUT_MS = 2000L

        /**
         * Builds the uncaught-exception handler installed by [installCrashLogger]: format the crash
         * via [CrashReporter], persist it as a CRASH-tagged app_logs row (synchronously, with a hard
         * timeout), then chain to [previous] so Crashlytics/system handling still runs. Extracted so
         * the handler→store wiring is testable without standing up Hilt — the DAO is supplied lazily
         * via [appLogDaoProvider] (in production, `{ appLogDao.get() }`). See
         * WeatherWidgetAppCrashHandlerTest.
         */
        internal fun buildCrashHandler(
            appLogDaoProvider: () -> AppLogDao,
            previous: Thread.UncaughtExceptionHandler?,
        ): Thread.UncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    val message = CrashReporter.formatCrashMessage(thread.name, throwable)
                    // The process is dying, so persist synchronously with a hard timeout. If the write
                    // hangs or fails, fall back to logcat — never let crash handling worsen the crash.
                    runBlocking {
                        withTimeoutOrNull(CRASH_PERSIST_TIMEOUT_MS) {
                            appLogDaoProvider().log(CrashReporter.CRASH_TAG, message, "ERROR")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(CrashReporter.CRASH_TAG, "Failed to persist crash", t)
                }
                previous?.uncaughtException(thread, throwable)
            }

        @Volatile
        private var processStartElapsedRealtime: Long = 0L

        fun processAgeMs(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long {
            val start = processStartElapsedRealtime
            return if (start > 0L) (nowElapsedRealtime - start).coerceAtLeast(0L) else Long.MAX_VALUE
        }

        // --- Cold-start trace (one line each per process lifetime) ----------------------------------
        // Anchored on [processAgeMs]. firstTrigger = first onReceive/onUpdate reaches the provider;
        // firstPaint = first widget actually pushed to AppWidgetManager. The gap between them isolates
        // "process started but render was slow"; the gap between process onCreate (its logcat
        // timestamp) and the triggering broadcast isolates "OS was slow to start the process".
        const val COLD_START_TAG = "COLD_START_TRACE"
        private val firstTriggerLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        private val firstPaintLogged = java.util.concurrent.atomic.AtomicBoolean(false)

        // The first-trigger age, captured so a persisted slow-paint row can split the latency into
        // "OS slow to deliver the broadcast" (large trigger age) vs "our render was slow" (large
        // paint-minus-trigger gap). -1 until the first trigger fires.
        @Volatile
        private var firstTriggerAgeMs: Long = -1L

        fun coldStartTriggerAgeMs(): Long = firstTriggerAgeMs

        fun logFirstTriggerOnce(via: String) {
            if (firstTriggerLogged.compareAndSet(false, true)) {
                firstTriggerAgeMs = processAgeMs()
                Log.i(COLD_START_TAG, "first_trigger ageMs=$firstTriggerAgeMs via=$via")
            }
        }

        /** Returns the process-age (ms) at the first paint, or -1 if this was not the first paint. */
        fun logFirstPaintOnce(appWidgetId: Int, view: String, path: String): Long {
            if (firstPaintLogged.compareAndSet(false, true)) {
                val ageMs = processAgeMs()
                Log.i(COLD_START_TAG, "first_paint ageMs=$ageMs widget=$appWidgetId view=$view path=$path")
                return ageMs
            }
            return -1L
        }
    }
}
