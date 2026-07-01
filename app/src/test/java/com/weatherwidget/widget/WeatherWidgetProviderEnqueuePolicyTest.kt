package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.weatherwidget.test.category.LongDuration
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
 * Guards the crash-prevention invariant for [WeatherWidgetProvider]'s worker enqueues: an
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
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `immediate UI-only update never cancels a running repaint`() {
        WeatherWidgetProvider.triggerUiOnlyUpdate(context, reason = "test", initialDelayMs = 0L)

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME + "_ui"),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `delayed UI-only clear may replace the pending not-running request`() {
        WeatherWidgetProvider.triggerUiOnlyUpdate(context, reason = "test", initialDelayMs = 5_000L)

        // Delayed work has no live coroutine to cancel, so REPLACE (keep only the latest clear) is safe.
        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME + "_ui_delayed"),
                eq(ExistingWorkPolicy.REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `immediate full-fetch update keeps a running sync instead of cancelling it`() {
        WeatherWidgetProvider.triggerImmediateUpdate(context, forceRefresh = true, reason = "test")

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
