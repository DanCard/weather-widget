package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real [DailyHistoryBackfillCoordinator] + [WidgetWorkScheduler] + WorkManager (test driver): a missing
 * source's forced refresh fetches that source alone (Pixel 2026-10-30: seven sources for OWM's actuals).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class MissingDataRefreshTargetRoboTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor { _ -> }
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    private fun request(forceRefresh: Boolean) = runBlocking {
        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.shouldRefreshMissingData(any(), any(), any(), any()) } returns true
        DailyHistoryBackfillCoordinator.requestMissingDataRefresh(
            context = context,
            appLogDao = mockk<AppLogDao>(relaxed = true),
            stateManager = stateManager,
            appWidgetId = 88,
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            refreshType = "actuals_today",
            cooldownMs = 0L,
            logTag = "MISSING_ACTUALS_FETCH",
            forceRefresh = forceRefresh,
            reason = "test",
            message = "test",
        )
    }

    private fun pendingTags() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_ONE_TIME).get(5, TimeUnit.SECONDS)
        .flatMap { it.tags }
        .filter { it.startsWith("forced_target:") }

    @Test
    fun `a forced missing-data refresh targets the displayed source`() {
        request(forceRefresh = true)
        assertEquals(listOf(WidgetWorkScheduler.forcedTargetTag(WeatherSource.OPEN_WEATHER_MAP.id)), pendingTags())
    }

    @Test
    fun `an unforced missing-data refresh forces nothing`() {
        request(forceRefresh = false)
        assertEquals(emptyList<String>(), pendingTags())
    }
}
