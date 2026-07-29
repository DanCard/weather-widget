package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.SharedPreferencesUtil
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

/**
 * Guards the crash-prevention invariant for [WidgetWorkScheduler]'s worker enqueues: an
 * *immediate* (running-capable) unique work must NEVER use [ExistingWorkPolicy.REPLACE], because
 * REPLACE cancels a running [WeatherWidgetWorker] and cancelling its coroutine continuation segfaults
 * the ART interpreter (see AGENTS.md "NEVER cancel a running WeatherWidgetWorker"). Only *delayed*
 * (not-yet-running) work may use REPLACE. These are the highest-churn enqueue sites, so a regression
 * here silently reintroduces the native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderEnqueuePolicyTest {

    private lateinit var context: Context
    private lateinit var mockWorkManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockWorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `immediate UI-only update never cancels a running repaint`() {
        WidgetWorkScheduler.enqueueUiRepaint(context, reason = "test")

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_UI),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `delayed UI-only clear uses a per-widget non-cancelling lane`() {
        WidgetWorkScheduler.enqueueDelayedUiRepaint(
            context = context,
            appWidgetId = 42,
            reason = "test",
            initialDelayMs = 5_000L,
        )

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.delayedUiWorkName(42)),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `immediate full-fetch update keeps a running sync instead of cancelling it`() {
        WidgetWorkScheduler.enqueueRedundantImmediateSync(context, forceRefresh = true, reason = "test")

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_ONE_TIME),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `required no-hourly follow-up is appended instead of discarded`() {
        WidgetWorkScheduler.enqueueRequiredNoHourlyFollowUp(
            context = context,
            appWidgetId = 17,
            date = "2026-07-30",
            lat = 37.42,
            lon = -122.08,
            targetSourceId = "NWS",
        )

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_ONE_TIME),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                match<OneTimeWorkRequest> {
                    it.workSpec.input.getInt(
                        WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID,
                        -1,
                    ) == 17
                },
            )
        }
    }

    @Test
    fun `delayed startup sync cannot occupy the urgent one-time lane`() {
        WidgetWorkScheduler.enqueueDelayedStartupSync(
            context = context,
            reason = "test",
            initialDelayMs = 60_000,
        )

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_STARTUP_DELAYED),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `periodic sync updates without cancelling a running instance`() {
        WidgetWorkScheduler.schedulePeriodicSync(context)

        verify(exactly = 1) {
            mockWorkManager.enqueueUniquePeriodicWork(
                eq(WidgetWorkScheduler.WORK_NAME_PERIODIC),
                eq(ExistingPeriodicWorkPolicy.UPDATE),
                any<PeriodicWorkRequest>(),
            )
        }
    }

    @Test
    fun `location candidate refresh keeps running handoff work instead of cancelling it`() {
        LocationUpdater.proposeFollowDeviceLocation(
            context = context,
            lat = 40.7128,
            lon = -74.0060,
            label = "Candidate",
            enqueueRefresh = true,
            nowMs = 100L,
            ids = intArrayOf(901),
        )

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetWorker.WORK_NAME_LOCATION_CANDIDATE),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
