package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Run-now current-temp requests against the charging loop's delayed heartbeat, on a real (test)
 * WorkManager: CurrentTempUpdateScheduler + WorkManager's unique-work chaining.
 *
 * Pixel 2026-10-14: both shared one APPEND_OR_REPLACE chain, so a tap was appended *behind* a
 * 16-minute heartbeat (BLOCKED) and ran up to 3.5 h later, or never
 * (performance/261014-current-temp-immediate-work-queued-behind-delayed-loop.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class CurrentTempRunNowQueueIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                // Never execute: the test observes chain states, not worker output.
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        setCharging()
    }

    @After
    fun tearDown() {
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `a run-now request is runnable at once even with a delayed heartbeat pending`() {
        val workManager = WorkManager.getInstance(context)
        runBlocking {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = workManager,
                nowMs = System.currentTimeMillis(),
                isScreenInteractive = false,
            )
        }
        val heartbeat = workManager.infos(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP).single()
        assertEquals(WorkInfo.State.ENQUEUED, heartbeat.state)
        assertEquals(TimeUnit.MINUTES.toMillis(com.weatherwidget.shared.util.CurrentTempCadence.CHARGING_SCREEN_OFF_MINUTES), heartbeat.initialDelayMillis)

        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "stale_on_refresh_action_cache_first",
            opportunistic = false,
            userInteraction = true,
        )

        val runNow = workManager.infos(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP_NOW).single()
        // ENQUEUED with no delay, not BLOCKED behind the heartbeat.
        assertEquals(WorkInfo.State.ENQUEUED, runNow.state)
        assertEquals(0L, runNow.initialDelayMillis)
        // And the heartbeat chain did not grow.
        assertEquals(1, workManager.infos(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP).size)
    }

    @Test
    fun `cancel clears the loop and the run-now chain`() {
        val workManager = WorkManager.getInstance(context)
        runBlocking {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = workManager,
                nowMs = System.currentTimeMillis(),
                isScreenInteractive = false,
            )
        }
        CurrentTempUpdateScheduler.enqueueImmediateUpdate(context, reason = "opportunistic_job", opportunistic = true)

        CurrentTempUpdateScheduler.cancel(context)

        val names = listOf(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP, WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP_NOW)
        for (name in names) {
            assertEquals(name, emptyList<WorkInfo>(), workManager.infos(name).filterNot { it.state.isFinished })
        }
    }

    private fun WorkManager.infos(name: String): List<WorkInfo> = getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    @Suppress("DEPRECATION")
    private fun setCharging() {
        context.sendStickyBroadcast(
            android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(android.os.BatteryManager.EXTRA_PLUGGED, android.os.BatteryManager.BATTERY_PLUGGED_AC)
                putExtra(android.os.BatteryManager.EXTRA_LEVEL, 50)
                putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            },
        )
    }
}
