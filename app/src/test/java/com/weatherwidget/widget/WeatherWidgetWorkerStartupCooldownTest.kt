package com.weatherwidget.widget

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.data.repository.SynopticObservationSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.LongDuration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The worker turns a run away during the startup cooldown: the run is re-enqueued with its input
 * intact, logged as deferred, and nothing that looks like a sync happens. Mirrors the 18:04:05
 * re-run of the killed forced sync on the Pixel
 * (`performance/260910-post-install-cold-start-storm.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetWorkerStartupCooldownTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(false)
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                // Deferred requests must stay ENQUEUED so the test can inspect them.
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @After
    fun teardown() {
        WeatherWidgetWorker.startupCooldownProvider = { com.weatherwidget.WeatherWidgetApp.startupCooldown() }
        db.close()
    }

    private fun inCooldown(): StartupCooldown =
        StartupCooldown(processStartElapsedMs = SystemClock.elapsedRealtime()).also {
            it.onUserFacingTrigger(SystemClock.elapsedRealtime())
        }

    private fun worker(input: Data): WeatherWidgetWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    WeatherWidgetWorker(
                        appContext,
                        workerParameters,
                        mockk<WeatherRepository>(relaxed = true),
                        mockk<WidgetStateManager>(relaxed = true),
                        db.appLogDao(),
                        mockk<GpsResampler>(relaxed = true),
                        mockk<MetarObservationSource>(relaxed = true),
                        mockk<SynopticObservationSource>(relaxed = true),
                    )
            }
        return TestListenableWorkerBuilder<WeatherWidgetWorker>(context)
            .setInputData(input)
            .setWorkerFactory(factory)
            .build()
    }

    private fun deferredInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_STARTUP_DEFERRED)
            .get(5, TimeUnit.SECONDS)

    @Test
    fun `a forced sync inside the cooldown is deferred with its input intact`() = runBlocking {
        WeatherWidgetWorker.startupCooldownProvider = { inCooldown() }
        val input =
            Data.Builder()
                .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "toggle_api_stale")
                .putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, "OPEN_METEO")
                .putLong(WeatherWidgetWorker.KEY_REQUESTED_AT_MS, 1_789_088_615_000L)
                .build()

        val result = worker(input).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val deferred = deferredInfos()
        assertEquals("exactly one deferred run", 1, deferred.size)
        assertEquals(WorkInfo.State.ENQUEUED, deferred.single().state)
        assertTrue(
            "deferred run carries the signature tag: ${deferred.single().tags}",
            deferred.single().tags.any { it.startsWith("startup_deferred:") },
        )
        val logs = db.appLogDao().getRecentLogs(50)
        assertTrue("SYNC_DEFERRED_STARTUP logged: ${logs.map { it.tag }}", logs.any { it.tag == "SYNC_DEFERRED_STARTUP" })
        assertTrue("no SYNC_START during the cooldown", logs.none { it.tag == "SYNC_START" })
        val message = logs.single { it.tag == "SYNC_DEFERRED_STARTUP" }.message
        assertTrue(message, "reason=toggle_api_stale" in message && "force=true" in message && "outcome=enqueued" in message)
    }

    @Test
    fun `an identical run arriving during the cooldown folds into the pending one`() = runBlocking {
        WeatherWidgetWorker.startupCooldownProvider = { inCooldown() }
        val input =
            Data.Builder()
                .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "refresh_tap")
                .build()

        worker(input).doWork()
        worker(input).doWork()

        assertEquals("three refresh taps are one refresh", 1, deferredInfos().size)
        val outcomes = db.appLogDao().getLogsByTag("SYNC_DEFERRED_STARTUP", 10).map { it.message }
        assertTrue(outcomes.toString(), outcomes.any { "outcome=coalesced" in it })
    }

    @Test
    fun `a different run appends behind the pending one`() = runBlocking {
        WeatherWidgetWorker.startupCooldownProvider = { inCooldown() }
        worker(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true).build()).doWork()
        worker(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true).build()).doWork()

        assertEquals("a current-temp run is not a full sync", 2, deferredInfos().size)
    }

    /** The one thing a cold process owes the user. */
    @Test
    fun `a UI-only repaint is never deferred`() = runBlocking {
        WeatherWidgetWorker.startupCooldownProvider = { inCooldown() }
        val input = Data.Builder().putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true).build()

        // The repaint proceeds into the pipeline against relaxed mocks; whatever it returns, it
        // must not have been parked in the deferred lane.
        runCatching { worker(input).doWork() }

        assertEquals(0, deferredInfos().size)
        assertTrue(db.appLogDao().getLogsByTag("SYNC_DEFERRED_STARTUP", 10).isEmpty())
    }
}
