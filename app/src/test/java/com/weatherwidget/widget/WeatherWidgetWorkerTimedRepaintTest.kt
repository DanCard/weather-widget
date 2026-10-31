package com.weatherwidget.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
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
import com.weatherwidget.shared.util.TimedRepaintGate
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
 * A timed repaint that starts during an interaction ends without syncing or painting, and a banner
 * schedules one repaint however many widgets show it.
 * plans/261031-taps-not-blocked-by-refresh-or-timed-repaints.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetWorkerTimedRepaintTest {
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
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        WidgetTimedRepaintGate.gate.resetForTesting()
        WidgetPreemptionSettler.clearForTesting()
    }

    @After
    fun teardown() {
        WidgetTimedRepaintGate.gate.resetForTesting()
        WidgetPreemptionSettler.clearForTesting()
        db.close()
    }

    private fun worker(reason: String): WeatherWidgetWorker {
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
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                    .build(),
            )
            .setWorkerFactory(factory)
            .build()
    }

    private suspend fun tags() = db.appLogDao().getRecentLogs(50).map { it.tag }

    @Test
    fun `a timed repaint during an interaction ends without running`() = runBlocking {
        WidgetTimedRepaintGate.gate.interactionStarted()

        val result = worker(WidgetTimedRepaintGate.REASON_UI_TICK).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val tags = tags()
        assertTrue("deferred: $tags", TimedRepaintGate.TAG_DEFERRED in tags)
        assertTrue("no sync started: $tags", "SYNC_START" !in tags)
    }

    @Test
    fun `a banner expiry defers like a tick`() = runBlocking {
        WidgetTimedRepaintGate.gate.interactionStarted()

        worker(WidgetTimedRepaintGate.REASON_NOTICE_EXPIRY).doWork()

        assertTrue("SYNC_START" !in tags())
    }

    @Test
    fun `a timed repaint runs when the user is idle`() = runBlocking {
        worker(WidgetTimedRepaintGate.REASON_UI_TICK).doWork()

        assertTrue("SYNC_START" in tags())
    }

    @Test
    fun `a repaint that is not timed runs during an interaction`() = runBlocking {
        WidgetTimedRepaintGate.gate.interactionStarted()

        worker("clear_location_change_banner").doWork()

        val tags = tags()
        assertTrue("SYNC_START" in tags)
        assertTrue(TimedRepaintGate.TAG_DEFERRED !in tags)
    }

    @Test
    fun `a banner on four widgets schedules one repaint`() {
        val ids = intArrayOf(1, 2, 3, 4)

        FetchBanner.show(context, "text", ids, reason = "test", showMs = 4_000L)

        val manager = WorkManager.getInstance(context)
        val scheduled = ids.map { id ->
            manager.getWorkInfosForUniqueWork(WidgetWorkScheduler.delayedUiWorkName(id)).get(5, TimeUnit.SECONDS).size
        }
        assertEquals(listOf(1, 0, 0, 0), scheduled)
    }
}
