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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Work queued in test mode must not hold a real request's place. Emulators 2026-11-01: a test's pending
 * startup sync made the real app's KEEP enqueue discard its own, and the test's then did nothing, so a
 * stale emulator never fetched. plans/261101-test-work-must-not-block-real-sync.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TestModeWorkNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hour = 3_600_000L

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun leaveTestMode() {
        WeatherDatabase.setIsTesting(false)
    }

    private fun pendingIds(name: String) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .map { it.id }

    @Test
    fun `a startup sync left by a test does not stop the real app queueing its own`() {
        WeatherDatabase.setIsTesting(true)
        val leftover = WidgetWorkScheduler.enqueueDelayedStartupSync(context, "on_update_stale", hour)

        WeatherDatabase.setIsTesting(false)
        val real = WidgetWorkScheduler.enqueueDelayedStartupSync(context, "on_update_stale", hour)

        assertEquals(listOf(real.id), pendingIds(WidgetWorkScheduler.WORK_NAME_STARTUP_DELAYED))
        assertEquals(listOf(leftover.id), pendingIds("${WidgetWorkScheduler.WORK_NAME_STARTUP_DELAYED}_test"))
    }

    @Test
    fun `the real app still merges its own repeated startup syncs`() {
        val first = WidgetWorkScheduler.enqueueDelayedStartupSync(context, "on_update_stale", hour)
        WidgetWorkScheduler.enqueueDelayedStartupSync(context, "on_update_stale", hour)

        assertEquals(listOf(first.id), pendingIds(WidgetWorkScheduler.WORK_NAME_STARTUP_DELAYED))
    }

    @Test
    fun `the name changes only in test mode`() {
        assertEquals("work", testScopedWorkName("work"))
        WeatherDatabase.setIsTesting(true)
        assertEquals("work_test", testScopedWorkName("work"))
    }

    @Test
    fun `in test mode a lookup reads the test queue, not the real one`() {
        fun enqueueUnder(name: String) = WorkManager.getInstance(context).enqueueUniqueWork(
            name,
            androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build(),
        ).result.get()

        enqueueUnder(WidgetWorkScheduler.WORK_NAME_ONE_TIME)
        WeatherDatabase.setIsTesting(true)
        assertNull("real work is not test mode's", WidgetWorkScheduler.pendingOneTimeSync(context))

        enqueueUnder("${WidgetWorkScheduler.WORK_NAME_ONE_TIME}_test")
        assertNotNull(WidgetWorkScheduler.pendingOneTimeSync(context))
    }
}
