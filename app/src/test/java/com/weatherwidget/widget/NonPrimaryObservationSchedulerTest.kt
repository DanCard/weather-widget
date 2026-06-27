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
import io.mockk.unmockkAll
import io.mockk.verify
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
class NonPrimaryObservationSchedulerTest {

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
    fun `loop schedules delayed work when no active work exists`() {
        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = emptyList(),
                nowMs = NOW_MS,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.ENQUEUE_DELAYED,
            reason = "no_active_work",
        )
    }

    @Test
    fun `loop keeps running non primary work`() {
        val active = workInfo(state = WorkInfo.State.RUNNING, nextScheduleTimeMs = NOW_MS - 5_000L)

        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.KEEP,
            reason = "running",
            active = active,
        )
    }

    @Test
    fun `loop ignores current worker when scheduling after run`() {
        val active = workInfo(state = WorkInfo.State.RUNNING, nextScheduleTimeMs = NOW_MS - 5_000L)

        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                ignoreRunningWorkId = active.id,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.ENQUEUE_DELAYED,
            reason = "no_active_work",
        )
    }

    @Test
    fun `loop keeps due soon enqueued non primary work`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS + TimeUnit.MINUTES.toMillis(15),
        )

        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.KEEP,
            reasonPrefix = "scheduled_in_ms=",
            active = active,
        )
    }

    @Test
    fun `loop recovers overdue enqueued non primary work immediately`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS - TimeUnit.MINUTES.toMillis(3),
        )

        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.REPLACE_IMMEDIATE,
            reasonPrefix = "overdue_by_ms=",
            active = active,
        )
    }

    @Test
    fun `loop replaces far future enqueued non primary work with corrected delay`() {
        val active = workInfo(
            state = WorkInfo.State.ENQUEUED,
            nextScheduleTimeMs = NOW_MS + TimeUnit.MINUTES.toMillis(40),
        )

        val decision =
            NonPrimaryObservationScheduler.decideLoopWork(
                workInfos = listOf(active),
                nowMs = NOW_MS,
                intervalMinutes = 30L
            )

        assertDecision(
            decision = decision,
            action = NonPrimaryObservationScheduler.ChargingLoopAction.REPLACE_DELAYED,
            reasonPrefix = "too_far_future_by_ms=",
            active = active,
        )
    }

    private fun workInfo(
        id: UUID = UUID.randomUUID(),
        state: WorkInfo.State,
        nextScheduleTimeMs: Long?,
    ): NonPrimaryObservationScheduler.ChargingWorkInfo =
        NonPrimaryObservationScheduler.ChargingWorkInfo(
            id = id,
            state = state,
            runAttemptCount = 0,
            nextScheduleTimeMs = nextScheduleTimeMs,
        )

    private fun assertDecision(
        decision: NonPrimaryObservationScheduler.ChargingLoopDecision,
        action: NonPrimaryObservationScheduler.ChargingLoopAction,
        reason: String? = null,
        reasonPrefix: String? = null,
        active: NonPrimaryObservationScheduler.ChargingWorkInfo? = null,
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
