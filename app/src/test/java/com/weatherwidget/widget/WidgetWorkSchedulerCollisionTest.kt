package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetWorkSchedulerCollisionTest {
    @Before
    fun initializeWorkManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                // Keep workers enqueued so the test observes collision semantics instead of
                // letting a synchronous WeatherWidgetWorker finish before the second enqueue.
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @Test
    fun `urgent refresh survives existing and delayed work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetWorkScheduler.enqueueRedundantImmediateSync(
            context,
            reason = "existing",
        )
        val delayed =
            WidgetWorkScheduler.enqueueDelayedStartupSync(
                context = context,
                reason = "startup",
                initialDelayMs = 60_000,
            )
        val urgent =
            WidgetWorkScheduler.enqueueRequiredImmediateSync(
                context = context,
                reason = "urgent",
            )
        val startupIds =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_STARTUP_DELAYED)
                .get(5, TimeUnit.SECONDS)
                .map { it.id }
        val updatedOneTimeIds =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_ONE_TIME)
                .get(5, TimeUnit.SECONDS)
                .map { it.id }

        assertTrue(startupIds.contains(delayed.id))
        assertTrue(updatedOneTimeIds.contains(urgent.id))
    }

    /**
     * KEEP used to fold a forced request for one source into a pending sync forced for another, which
     * never fetches it (Samsung 2026-10-15: NWS 375 min old, absorbed by an Open-Meteo-only sync).
     * A targeted request now queues behind it; one the pending work already covers still joins it.
     */
    @Test
    fun `a forced sync for another source queues behind the pending one, a covered one joins it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun forced(target: String?) = WidgetWorkScheduler.enqueueForcedSync(
            context, reason = "test", policy = androidx.work.ExistingWorkPolicy.KEEP, targetSourceId = target,
            // Held so it stays pending: undelayed work runs (and fails) at once under the test driver.
            initialDelayMs = 60_000,
        )
        fun pendingTargets() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_ONE_TIME).get(5, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
            .map { info -> info.tags.single { it.startsWith("forced_target:") } }

        assertEquals(false, forced(WeatherSource.OPEN_METEO.id).followUp)
        assertEquals("another source queues behind", true, forced(WeatherSource.NWS.id).followUp)
        assertEquals("already queued for NWS", false, forced(WeatherSource.NWS.id).followUp)
        assertEquals("untargeted keeps KEEP", false, forced(null).followUp)
        assertEquals(setOf("forced_target:OPEN_METEO", "forced_target:NWS"), pendingTargets().toSet())
        assertEquals(2, pendingTargets().size)
    }

    @Test
    fun `only a sync forced for this source covers a target`() {
        val all = setOf(WidgetWorkScheduler.forcedTargetTag(null))
        val meteo = setOf(WidgetWorkScheduler.forcedTargetTag("OPEN_METEO"))
        assertEquals(false, WidgetWorkScheduler.needsForcedFollowUp(emptyList(), "NWS"))
        assertEquals("an untargeted sync may skip a gated source", true, WidgetWorkScheduler.needsForcedFollowUp(listOf(all), "NWS"))
        assertEquals(true, WidgetWorkScheduler.needsForcedFollowUp(listOf(meteo), "NWS"))
        assertEquals("an unforced sync fetches nothing on demand", true, WidgetWorkScheduler.needsForcedFollowUp(listOf(emptySet()), "NWS"))
        assertEquals(false, WidgetWorkScheduler.needsForcedFollowUp(listOf(meteo), "OPEN_METEO"))
    }

    /**
     * The backfill is the one carve-out from this class's APPEND_OR_REPLACE rule (plan 260820).
     *
     * It used to append, which makes a pending backfill a *prerequisite* of the next one: a burst
     * became a serial queue of identical 5-station, 72-hour fetches, and the actuals the user was
     * waiting on sat behind the lot. Six stacked up in five minutes on the emulator. The work is
     * idempotent and carries no callback, so a second request buys nothing.
     */
    @Test
    fun `a second observation backfill collapses into the pending one`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val older =
            WidgetWorkScheduler.enqueueRequiredObservationBackfill(
                context = context,
                latitude = 37.417,
                longitude = -122.089,
                lookbackHours = 72,
                reason = "older_window",
                initialDelayMs = 60_000,
            ).request
        val second =
            WidgetWorkScheduler.enqueueRequiredObservationBackfill(
                context = context,
                latitude = 37.417,
                longitude = -122.089,
                lookbackHours = 72,
                reason = "newer_window",
                initialDelayMs = 60_000,
            )
        assertEquals(
            "a request still inside its delay is pending, not wedged",
            WidgetWorkScheduler.BackfillEnqueueOutcome.KEPT_PENDING,
            second.outcome,
        )

        val retainedIds =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL)
                .get(5, TimeUnit.SECONDS)
                .map { it.id }

        assertEquals("the burst must not chain", 1, retainedIds.size)
        assertTrue("the pending request is the one that survives", retainedIds.contains(older.id))
    }

    /**
     * The wedge KEEP cannot escape on its own: nothing else in the app cancels
     * [WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL], so one workspec that is accepted but
     * never dispatched disables observation repair for good. Emulator 2026-09-10 — job 26816 sat
     * ready with every constraint satisfied while the NWS actuals line drew a straight
     * interpolation across an 8-hour hole.
     */
    @Test
    fun `an overdue pending backfill is replaced rather than deferred to`() {
        val nowMs = 1_000_000_000L
        val overdue =
            WidgetWorkScheduler.PendingBackfillWork(
                id = UUID.randomUUID(),
                state = WorkInfo.State.ENQUEUED,
                nextScheduleTimeMs = nowMs - WidgetWorkScheduler.BACKFILL_OVERDUE_GRACE_MS - 60_000L,
            )

        val (outcome, _) =
            WidgetWorkScheduler.decideObservationBackfillEnqueue(listOf(overdue), nowMs)

        assertEquals(WidgetWorkScheduler.BackfillEnqueueOutcome.REPLACED_OVERDUE, outcome)
    }

    @Test
    fun `a backfill still inside its grace is kept`() {
        val nowMs = 1_000_000_000L
        val waiting =
            WidgetWorkScheduler.PendingBackfillWork(
                id = UUID.randomUUID(),
                state = WorkInfo.State.ENQUEUED,
                nextScheduleTimeMs = nowMs - 60_000L,
            )

        val (outcome, _) =
            WidgetWorkScheduler.decideObservationBackfillEnqueue(listOf(waiting), nowMs)

        assertEquals(WidgetWorkScheduler.BackfillEnqueueOutcome.KEPT_PENDING, outcome)
    }

    /**
     * The fetch is idempotent, so cancelling one already talking to the network gains nothing — and
     * cancelling a running worker mid-coroutine is its own hazard.
     */
    @Test
    fun `a running backfill is never replaced however overdue it looks`() {
        val nowMs = 1_000_000_000L
        val running =
            WidgetWorkScheduler.PendingBackfillWork(
                id = UUID.randomUUID(),
                state = WorkInfo.State.RUNNING,
                nextScheduleTimeMs = nowMs - TimeUnit.HOURS.toMillis(6),
            )

        val (outcome, _) =
            WidgetWorkScheduler.decideObservationBackfillEnqueue(listOf(running), nowMs)

        assertEquals(WidgetWorkScheduler.BackfillEnqueueOutcome.KEPT_PENDING, outcome)
    }

    /** Finished work does not hold the unique name, so it must not look like a pending request. */
    @Test
    fun `finished work leaves the name free`() {
        val nowMs = 1_000_000_000L
        val done =
            WidgetWorkScheduler.PendingBackfillWork(
                id = UUID.randomUUID(),
                state = WorkInfo.State.SUCCEEDED,
                nextScheduleTimeMs = Long.MAX_VALUE,
            )

        val (outcome, _) =
            WidgetWorkScheduler.decideObservationBackfillEnqueue(listOf(done), nowMs)

        assertEquals(WidgetWorkScheduler.BackfillEnqueueOutcome.ENQUEUED, outcome)
    }

    /**
     * `Long.MAX_VALUE` is WorkManager's "not scheduled again" sentinel. Subtracting it would make an
     * unscheduled request look hours overdue and replace it on every pass.
     */
    @Test
    fun `an unscheduled pending request is not mistaken for overdue`() {
        val nowMs = 1_000_000_000L
        val unscheduled =
            WidgetWorkScheduler.PendingBackfillWork(
                id = UUID.randomUUID(),
                state = WorkInfo.State.BLOCKED,
                nextScheduleTimeMs = Long.MAX_VALUE,
            )

        val (outcome, _) =
            WidgetWorkScheduler.decideObservationBackfillEnqueue(listOf(unscheduled), nowMs)

        assertEquals(WidgetWorkScheduler.BackfillEnqueueOutcome.KEPT_PENDING, outcome)
    }

    @Test
    fun `settings refresh carries all-sources into the worker input, widget refresh does not`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = WidgetWorkScheduler.enqueueRequiredImmediateSync(
            context = context,
            reason = "settings_manual_refresh",
            userRefresh = true,
            allSources = true,
        )
        val widget = WidgetWorkScheduler.enqueueRequiredImmediateSync(
            context = context,
            reason = "widget_tap_to_refresh",
            userRefresh = true,
        )
        val settingsInput = WorkInput.from(settings.workSpec.input)
        assertTrue(settingsInput.allSources)
        assertTrue(settingsInput.userRefresh)
        assertEquals(false, WorkInput.from(widget.workSpec.input).allSources)
        assertTrue(
            "a deferred settings refresh must not fold into a widget refresh",
            WidgetWorkScheduler.deferredSignature(settings.workSpec.input) != WidgetWorkScheduler.deferredSignature(widget.workSpec.input),
        )
    }
}
