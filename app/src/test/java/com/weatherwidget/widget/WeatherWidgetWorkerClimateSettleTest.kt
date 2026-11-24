package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
 * Verifies plans/261122-climate-normals-retry-on-routine-refresh.md on Android:
 * - `currentTempOnly` settles climate normals in parallel with the current-temp fetch; the fetch
 *   completes while the archive is gated, and the worker waits for the child before finishing.
 * - `nonPrimaryCurrentTempOnly` never calls `settleClimateNormals`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetWorkerClimateSettleTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder()
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        WeatherDatabase.setIsTesting(false)
        WeatherWidgetWorker.startupCooldownProvider = { StartupCooldown(processStartElapsedMs = 0L) }
        WidgetTimedRepaintGate.gate.resetForTesting()
        WidgetBackfillDeferral.policy.resetForTesting()
        db = TestDatabase.create()
        ActiveLocationResolver.persist(context, 37.4220, -122.0841)

        repository = mockk(relaxed = true)
        coEvery { repository.refreshCurrentTemperature(any(), any(), any(), any(), any()) } returns Result.success(0)
        coEvery { repository.getWeatherData(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(emptyList())
        stateManager = mockk(relaxed = true)
        every { stateManager.getPrimarySource() } returns WeatherSource.NWS
        every { stateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        every { stateManager.notifyOnDominantTempChange() } returns false
    }

    @After
    fun tearDown() {
        WeatherWidgetWorker.startupCooldownProvider = { com.weatherwidget.WeatherWidgetApp.startupCooldown() }
        WidgetTimedRepaintGate.gate.resetForTesting()
        WidgetBackfillDeferral.policy.resetForTesting()
        WeatherDatabase.resetInstanceForTesting()
        db.close()
    }

    private fun worker(inputData: androidx.work.Data): WeatherWidgetWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                WeatherWidgetWorker(
                    appContext,
                    workerParameters,
                    repository,
                    stateManager,
                    db.appLogDao(),
                    mockk<GpsResampler>(relaxed = true),
                    mockk<MetarObservationSource>(relaxed = true),
                )
        }
        return TestListenableWorkerBuilder<WeatherWidgetWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `currentTempOnly settles climate normals in parallel and waits for the child before finishing`() = runBlocking {
        val settleStarted = CompletableDeferred<Unit>()
        val releaseSettle = CompletableDeferred<Unit>()
        val fetchDone = CompletableDeferred<Unit>()

        coEvery { repository.settleClimateNormals(any(), any(), any()) } coAnswers {
            settleStarted.complete(Unit)
            releaseSettle.await()
            true
        }
        coEvery { repository.refreshCurrentTemperature(any(), any(), any(), any(), any()) } coAnswers {
            fetchDone.complete(Unit)
            Result.success(1)
        }

        val w = worker(
            androidx.work.workDataOf(
                WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY to true,
                WeatherWidgetWorker.KEY_FORCE_REFRESH to true,
                WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON to "manual_test",
            ),
        )

        val work = async { w.doWork() }
        kotlinx.coroutines.withTimeout(30_000L) {
            settleStarted.await()
            fetchDone.await()
        }
        assertFalse("worker must wait for the climate-normals child before returning", work.isCompleted)

        releaseSettle.complete(Unit)
        val result = kotlinx.coroutines.withTimeout(30_000L) { work.await() }
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { repository.settleClimateNormals(any(), any(), "current_temp") }
    }

    @Test
    fun `nonPrimaryCurrentTempOnly never calls settleClimateNormals`() = runBlocking {
        val w = worker(
            androidx.work.workDataOf(
                WeatherWidgetWorker.KEY_NONPRIMARY_CURRENT_TEMP_ONLY to true,
                WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON to "nonprimary_test",
            ),
        )
        val result = w.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { repository.settleClimateNormals(any(), any(), any()) }
    }
}
