package com.weatherwidget.widget

import android.content.Context
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
import com.weatherwidget.widget.handlers.HourlyBackfillGate
import com.weatherwidget.widget.handlers.observationBackfillSiteKey
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real worker + real [WidgetStateManager] + test WorkManager + [HourlyBackfillGate]: the cooldown
 * runs from a backfill that STARTED its fetch. A request dropped before it ran is asked again; an
 * attempt that throws still cools down, so a crashing backfill is not re-requested on every repaint.
 * See plans/261009-observation-backfill-cooldown-starts-when-it-runs.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ObservationBackfillAttemptIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private val lat = 37.4166
    private val lon = -122.0889
    private val siteKey get() = observationBackfillSiteKey(lat, lon)
    private val requestKey = "NWS_HOURLY_HISTORY_37.417_-122.089"
    private val cooldownMs = 30 * 60_000L

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(false)
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        stateManager = WidgetStateManager(context)
        // Long past any startup cooldown, so the worker runs the backfill handler.
        WeatherWidgetWorker.startupCooldownProvider = { StartupCooldown(processStartElapsedMs = -3_600_000L) }
    }

    @After
    fun teardown() {
        WeatherWidgetWorker.startupCooldownProvider = { com.weatherwidget.WeatherWidgetApp.startupCooldown() }
        db.close()
    }

    private fun gate() = runBlocking {
        HourlyBackfillGate.decide(
            nowMs = stateManager.fetchStateNowMs(),
            cooldownMs = cooldownMs,
            requestedAtMs = stateManager.missingActualsRequestedAtMs(7, requestKey),
            attemptedAtMs = stateManager.observationBackfillAttemptedAtMs(siteKey),
            attemptTracked = true,
            isPending = { WidgetWorkScheduler.hasUnfinishedObservationBackfill(context) },
        )
    }

    private fun backfillWorker(repository: WeatherRepository): WeatherWidgetWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                WeatherWidgetWorker(
                    appContext, workerParameters, repository, stateManager, db.appLogDao(),
                    mockk<GpsResampler>(relaxed = true), mockk<MetarObservationSource>(relaxed = true),
                    mockk<SynopticObservationSource>(relaxed = true),
                )
        }
        val input = Data.Builder()
            .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, lat)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, lon)
            .putLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, 72L)
            .putString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON, "test_gap")
            .build()
        return TestListenableWorkerBuilder<WeatherWidgetWorker>(context).setInputData(input).setWorkerFactory(factory).build()
    }

    /** Emulator 2026-10-09: the request was consumed by a test process and never reached the fetch. */
    @Test
    fun `a request that never ran is asked again`() {
        stateManager.markMissingActualsRefreshRequested(7, requestKey)

        val decision = gate()

        assertTrue(decision.reason, decision.request)
        assertTrue(decision.reason, decision.reason.startsWith("dropped"))
    }

    @Test
    fun `a backfill that throws still stamps its attempt and cools down`() = runBlocking {
        stateManager.markMissingActualsRefreshRequested(7, requestKey)
        val repository = mockk<WeatherRepository>(relaxed = true)
        coEvery { repository.backfillRecentNwsObservations(any(), any(), any()) } throws IllegalStateException("boom")

        val result = backfillWorker(repository).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertTrue("attempt stamped before the fetch", stateManager.observationBackfillAttemptedAtMs(siteKey) > 0L)
        val decision = gate()
        assertFalse("a crashed attempt must not be re-requested: ${decision.reason}", decision.request)
        assertTrue(decision.reason, decision.reason.startsWith("cooldown"))
    }

    /**
     * Emulator 2026-10-09 18:14:06: the request came due in a cold process and the startup cooldown
     * re-queued it into the deferred lane, finishing the original as SUCCEEDED. The gate read that as
     * dropped and a second backfill ran 4 s after the first. A deferred backfill is pending work.
     */
    @Test
    fun `a backfill parked by the startup cooldown is pending, not dropped, and is not enqueued twice`() = runBlocking {
        stateManager.markMissingActualsRefreshRequested(7, requestKey)
        val backfillInput = Data.Builder()
            .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, lat)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, lon)
            .build()
        WidgetWorkScheduler.enqueueStartupDeferred(context, backfillInput, delayMs = 15_000L, excludeId = java.util.UUID.randomUUID())

        val decision = gate()
        assertFalse("deferred replay must count as pending: ${decision.reason}", decision.request)
        assertTrue(decision.reason, decision.reason.startsWith("pending"))

        val enqueue = WidgetWorkScheduler.enqueueRequiredObservationBackfill(
            context, lat, lon, lookbackHours = 72L, reason = "test_gap", initialDelayMs = 15_000L,
        )
        assertEquals("startup_deferred", enqueue.detail)
        val ownLane = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL).get()
        assertTrue("no second backfill in its own lane: $ownLane", ownLane.none { !it.state.isFinished })
    }
}
