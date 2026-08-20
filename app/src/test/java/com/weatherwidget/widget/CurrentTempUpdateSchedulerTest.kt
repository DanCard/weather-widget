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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
