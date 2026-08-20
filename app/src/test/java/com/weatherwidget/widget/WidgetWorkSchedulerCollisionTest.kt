package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import java.util.concurrent.TimeUnit
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
    fun `required callback and urgent refresh survive existing and delayed work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetWorkScheduler.enqueueRedundantImmediateSync(
            context,
            reason = "existing",
        )
        val followUp =
            WidgetWorkScheduler.enqueueRequiredNoHourlyFollowUp(
                context = context,
                appWidgetId = 82,
                date = "2026-07-30",
                lat = 37.42,
                lon = -122.08,
                targetSourceId = WeatherSource.NWS.id,
            )

        val oneTimeIds =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_ONE_TIME)
                .get(5, TimeUnit.SECONDS)
                .map { it.id }
        assertTrue(oneTimeIds.contains(followUp.id))
        assertEquals(
            82,
            followUp.workSpec.input.getInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, -1),
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
     * The backfill is the one carve-out from this class's APPEND_OR_REPLACE rule (plan 260820).
     *
     * It used to append, which makes a pending backfill a *prerequisite* of the next one: a burst
     * became a serial queue of identical 5-station, 72-hour fetches, and the actuals the user was
     * waiting on sat behind the lot. Six stacked up in five minutes on the emulator. The work is
     * idempotent and carries no callback, so a second request buys nothing.
     */
    @Test
    fun `a second observation backfill collapses into the pending one`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val older =
            WidgetWorkScheduler.enqueueRequiredObservationBackfill(
                context = context,
                latitude = 37.417,
                longitude = -122.089,
                lookbackHours = 72,
                reason = "older_window",
                initialDelayMs = 60_000,
            )
        WidgetWorkScheduler.enqueueRequiredObservationBackfill(
            context = context,
            latitude = 37.417,
            longitude = -122.089,
            lookbackHours = 72,
            reason = "newer_window",
            initialDelayMs = 60_000,
        )

        val retainedIds =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL)
                .get(5, TimeUnit.SECONDS)
                .map { it.id }

        assertEquals("the burst must not chain", 1, retainedIds.size)
        assertTrue("the pending request is the one that survives", retainedIds.contains(older.id))
    }

}