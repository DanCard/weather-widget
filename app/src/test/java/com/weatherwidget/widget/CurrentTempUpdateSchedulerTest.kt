package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class CurrentTempUpdateSchedulerTest {

    private lateinit var context: Context
    private lateinit var mockWorkManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())

        mockWorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `charging loop schedules delayed work when no active work exists`() {
        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = emptyList(),
                nowMs = NOW_MS,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.ENQUEUE_DELAYED,
            reason = "no_active_work",
        )
    }

    @Test
    fun `charging loop keeps running current temp work`() {
        val active = workInfo(state = WorkInfo.State.RUNNING, nextScheduleTimeMs = NOW_MS - 5_000L)

        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.KEEP,
            reason = "running",
            active = active,
        )
    }

    @Test
    fun `charging loop ignores current worker when scheduling after run`() {
        val active = workInfo(state = WorkInfo.State.RUNNING, nextScheduleTimeMs = NOW_MS - 5_000L)

        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                ignoreRunningWorkId = active.id,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.ENQUEUE_DELAYED,
            reason = "no_active_work",
        )
    }

    @Test
    fun `charging loop keeps due soon enqueued current temp work`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS + TimeUnit.MINUTES.toMillis(3),
        )

        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.KEEP,
            reasonPrefix = "scheduled_in_ms=",
            active = active,
        )
    }

    @Test
    fun `charging loop recovers overdue enqueued current temp work immediately`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS - TimeUnit.MINUTES.toMillis(3),
        )

        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.REPLACE_IMMEDIATE,
            reasonPrefix = "overdue_by_ms=",
            active = active,
        )
    }

    @Test
    fun `charging loop replaces far future enqueued current temp work with corrected delay`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS + TimeUnit.MINUTES.toMillis(20),
        )

        val decision =
            CurrentTempUpdateScheduler.decideChargingLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
            )

        assertDecision(
            decision = decision,
            action = CurrentTempUpdateScheduler.ChargingLoopAction.REPLACE_DELAYED,
            reasonPrefix = "too_far_future_by_ms=",
            active = active,
        )
    }

    @Test
    fun `immediate current temp update does not cancel running work`() {
        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "manual_test",
            opportunistic = false,
        )

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP),
                // APPEND_OR_REPLACE, not REPLACE: cancelling a running current-temp worker segfaults
                // ART on debuggable builds. Callers are opportunistic (screen-on/power/opportunistic),
                // so running after an in-flight fetch instead of cancelling it is fine.
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `immediate current temp update passes targeted source to worker`() {
        val requestSlot = slot<OneTimeWorkRequest>()

        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "opportunistic_job",
            opportunistic = true,
            targetSourceId = "NWS",
        )

        verify {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                capture(requestSlot),
            )
        }
        assertEquals(
            "NWS",
            requestSlot.captured.workSpec.input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE),
        )
    }

    @Test
    fun `immediate current temp update passes user interaction flag to worker`() {
        val requestSlot = slot<OneTimeWorkRequest>()

        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "stale_on_set_view",
            opportunistic = false,
            userInteraction = true,
        )

        verify {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                capture(requestSlot),
            )
        }
        assertEquals(
            true,
            requestSlot.captured.workSpec.input.getBoolean(WeatherWidgetWorker.KEY_USER_INTERACTION, false),
        )
    }

    @Test
    fun `scheduleNextChargingUpdate uses APPEND_OR_REPLACE when enqueuing successor from running worker`() {
        // Use a real WorkInfo if possible, but it's easier to mock it
        val activeId = UUID.randomUUID()
        val mockWorkInfo = mockk<WorkInfo>()
        every { mockWorkInfo.id } returns activeId
        every { mockWorkInfo.state } returns WorkInfo.State.RUNNING
        every { mockWorkInfo.runAttemptCount } returns 0
        every { mockWorkInfo.nextScheduleTimeMillis } returns NOW_MS - 5_000L

        every { mockWorkManager.getWorkInfosForUniqueWork(any()) } returns com.google.common.util.concurrent.Futures.immediateFuture(listOf(mockWorkInfo))
        setBattery(level = 50, charging = true)

        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = mockWorkManager,
                nowMs = NOW_MS,
                ignoreRunningWorkId = activeId,
            )
        }

        verify {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>()
            )
        }
    }

    // 75%, not higher: at >= 78% a first discharging reading is inferred as a held charge
    // (BatteryTier.HELD_CHARGE_MIN_LEVEL) and the snapshot reports charging.
    @Test
    fun `on battery at 75 percent with screen on the loop arms a 20-minute alarm, not a delayed WorkManager request`() {
        every { mockWorkManager.getWorkInfosForUniqueWork(any()) } returns
            com.google.common.util.concurrent.Futures.immediateFuture(emptyList())
        setBattery(level = 75, charging = false)

        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = mockWorkManager,
                nowMs = NOW_MS,
                isScreenInteractive = true,
            )
        }

        verify(exactly = 0) { mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        // Allow-while-idle (exempt from Adaptive Battery Saver), placed so the system's 75% window
        // ends 25 min out: delivered ~14.3-25 min.
        val alarm = shadowOf(alarmManager()).scheduledAlarms.single()
        assertEquals(timingFor(nominalMinutes = 20).triggerMs, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(android.app.AlarmManager.RTC, alarm.type)
    }

    @Test
    fun `screen-on first delay shortens the first battery alarm`() {
        setBattery(level = 75, charging = false)

        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = mockWorkManager,
                nowMs = NOW_MS,
                isScreenInteractive = true,
                firstDelayMinutes = 7L,
            )
        }

        assertEquals(timingFor(nominalMinutes = 7).triggerMs, shadowOf(alarmManager()).scheduledAlarms.single().triggerAtTime)
    }

    /**
     * The ui_update_alarm heartbeat asks for the loop every 15-60 min. If each ask re-armed at
     * now + 20, a 15-minute heartbeat would postpone the fetch forever.
     */
    @Test
    fun `a later request never postpones a pending battery alarm`() {
        setBattery(level = 75, charging = false)
        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context, workManager = mockWorkManager, nowMs = NOW_MS, isScreenInteractive = true,
            )
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context, workManager = mockWorkManager, nowMs = NOW_MS + TimeUnit.MINUTES.toMillis(15),
                isScreenInteractive = true,
            )
        }

        assertEquals(timingFor(nominalMinutes = 20).triggerMs, shadowOf(alarmManager()).scheduledAlarms.single().triggerAtTime)
    }

    private fun timingFor(nominalMinutes: Long) =
        BatteryObservationAlarm.timing(NOW_MS + TimeUnit.MINUTES.toMillis(nominalMinutes), NOW_MS)

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

    @Test
    fun `on battery below 70 percent or with screen off the loop schedules nothing`() {
        every { mockWorkManager.getWorkInfosForUniqueWork(any()) } returns
            com.google.common.util.concurrent.Futures.immediateFuture(emptyList())

        setBattery(level = 69, charging = false)
        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context, workManager = mockWorkManager, nowMs = NOW_MS, isScreenInteractive = true,
            )
        }
        setBattery(level = 75, charging = false)
        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context, workManager = mockWorkManager, nowMs = NOW_MS, isScreenInteractive = false,
            )
        }

        verify(exactly = 0) { mockWorkManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        assertEquals(0, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    @Test
    fun `charging loop keeps fetching every visible source`() {
        every { mockWorkManager.getWorkInfosForUniqueWork(any()) } returns
            com.google.common.util.concurrent.Futures.immediateFuture(emptyList())
        setBattery(level = 50, charging = true)
        val requestSlot = slot<OneTimeWorkRequest>()

        kotlinx.coroutines.test.runTest {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context, workManager = mockWorkManager, nowMs = NOW_MS, isScreenInteractive = true,
            )
        }

        verify { mockWorkManager.enqueueUniqueWork(any(), any(), capture(requestSlot)) }
        val spec = requestSlot.captured.workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(10), spec.initialDelay)
        assertEquals("charging_loop", spec.input.getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON))
        assertEquals(null, spec.input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
    }

    @Suppress("DEPRECATION")
    private fun setBattery(level: Int, charging: Boolean) {
        context.sendStickyBroadcast(
            android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(
                    android.os.BatteryManager.EXTRA_STATUS,
                    if (charging) android.os.BatteryManager.BATTERY_STATUS_CHARGING else android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
                )
                putExtra(android.os.BatteryManager.EXTRA_PLUGGED, if (charging) android.os.BatteryManager.BATTERY_PLUGGED_AC else 0)
                putExtra(android.os.BatteryManager.EXTRA_LEVEL, level)
                putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            },
        )
    }

    private fun workInfo(
        id: UUID = UUID.randomUUID(),
        state: WorkInfo.State,
        nextScheduleTimeMs: Long?,
    ): CurrentTempUpdateScheduler.ChargingWorkInfo =
        CurrentTempUpdateScheduler.ChargingWorkInfo(
            id = id,
            state = state,
            runAttemptCount = 0,
            nextScheduleTimeMs = nextScheduleTimeMs,
        )

    private fun assertDecision(
        decision: CurrentTempUpdateScheduler.ChargingLoopDecision,
        action: CurrentTempUpdateScheduler.ChargingLoopAction,
        reason: String? = null,
        reasonPrefix: String? = null,
        active: CurrentTempUpdateScheduler.ChargingWorkInfo? = null,
    ) {
        org.junit.Assert.assertEquals(action, decision.action)
        if (reason != null) {
            org.junit.Assert.assertEquals(reason, decision.reason)
        }
        if (reasonPrefix != null) {
            org.junit.Assert.assertTrue(decision.reason.startsWith(reasonPrefix))
        }
        org.junit.Assert.assertEquals(active, decision.active)
    }

    private companion object {
        const val NOW_MS = 1_779_205_825_000L
    }
}
