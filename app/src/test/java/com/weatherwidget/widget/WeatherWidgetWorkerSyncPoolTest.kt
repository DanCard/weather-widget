package com.weatherwidget.widget

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Which worker runs go to the low-priority sync pool: everything that is not a repaint
 * (`performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`, phase 2). The pool is
 * replaced by a recording dispatcher that runs the work inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetWorkerSyncPoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private val dispatched = AtomicInteger(0)

    private val recordingPool =
        object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                block.run()
            }
        }

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(false)
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor { _ -> }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        // No user-facing trigger and no pending paint: nothing is deferred.
        WeatherWidgetWorker.startupCooldownProvider = { StartupCooldown(processStartElapsedMs = SystemClock.elapsedRealtime()) }
        WeatherWidgetWorker.syncDispatcherProvider = { recordingPool }
    }

    @After
    fun teardown() {
        WeatherWidgetWorker.startupCooldownProvider = { com.weatherwidget.WeatherWidgetApp.startupCooldown() }
        WeatherWidgetWorker.syncDispatcherProvider = { SyncDispatcher.dispatcher }
        db.close()
    }

    private fun run(input: Data) {
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
        val worker = TestListenableWorkerBuilder<WeatherWidgetWorker>(context).setInputData(input).setWorkerFactory(factory).build()
        // The run proceeds against relaxed mocks; only where it ran matters here.
        val outcome = runCatching { runBlocking { worker.doWork() } }
        println("WeatherWidgetWorkerSyncPoolTest: input=${input.keyValueMap} outcome=$outcome dispatched=${dispatched.get()}")
    }

    private fun syncStarted(): Boolean = runBlocking { db.appLogDao().getLogsByTag("SYNC_START", 10).isNotEmpty() }

    @Test
    fun `a current-temp sync runs on the sync pool`() {
        run(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true).build())

        assertTrue("the run got as far as SYNC_START", syncStarted())
        assertTrue("dispatched to the pool: ${dispatched.get()}", dispatched.get() >= 1)
    }

    @Test
    fun `a full sync runs on the sync pool`() {
        run(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true).build())

        assertTrue("the run got as far as SYNC_START", syncStarted())
        assertTrue("dispatched to the pool: ${dispatched.get()}", dispatched.get() >= 1)
    }

    @Test
    fun `an observation backfill runs on the sync pool`() {
        run(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true).build())

        assertTrue("the run got as far as SYNC_START", syncStarted())
        assertTrue("dispatched to the pool: ${dispatched.get()}", dispatched.get() >= 1)
    }

    /** The repaint is the user-visible part; it stays at normal priority. */
    @Test
    fun `a UI-only repaint never touches the sync pool`() {
        run(Data.Builder().putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true).build())

        assertTrue("the run got as far as SYNC_START", syncStarted())
        assertEquals(0, dispatched.get())
    }
}
