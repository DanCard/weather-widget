package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android's due-time sync — the shared ForecastSchedule's wake as one delayed WorkManager sync — and
 * the battery screen-on re-plan that may only bring it forward.
 * plans/261028-screen-off-cadence-and-due-time-wakes.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DueWakeSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val minute = 60_000L
    private val hour = 60 * minute

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        SharedPreferencesUtil.getPrefs(context, "due_wake_prefs").edit().clear().commit()
    }

    private fun pending(): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_DUE_WAKE).get()
            .singleOrNull { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `the due-time sync is one delayed networked sync, and a new plan replaces a pending one`() {
        WidgetWorkScheduler.enqueueDueWakeSync(context, 5 * hour)
        val first = pending()!!
        assertEquals(5 * hour, first.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        WidgetWorkScheduler.enqueueDueWakeSync(context, 2 * hour)
        assertEquals(2 * hour, pending()!!.initialDelayMillis)
    }

    @Test
    fun `a screen-on re-plan only brings the wake forward`() {
        WidgetWorkScheduler.enqueueDueWakeSync(context, 2 * hour)
        assertNull("later than planned: kept", WidgetWorkScheduler.enqueueDueWakeSync(context, 3 * hour, onlyIfEarlier = true))
        assertEquals(2 * hour, pending()!!.initialDelayMillis)
        assertNotNull(WidgetWorkScheduler.enqueueDueWakeSync(context, hour, onlyIfEarlier = true))
        assertEquals(hour, pending()!!.initialDelayMillis)
    }

    @Test
    fun `battery screen-on moves the due time to the 4 h screen-on interval`() {
        val manager = WidgetStateManager(context)
        manager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        val displayed = manager.getActiveDisplaySourceIds().single()
        val lat = 37.42
        val lon = -122.08
        val now = 1_800_000_000_000L
        manager.markLongFetch(displayed, lat, lon, now - 2 * hour)
        val dueAt = ScreenOnReceiver.screenOnForecastDueAtMs(manager, lat, lon, now, batteryLevel = 85)!!
        assertTrue("≈ 2 h out (4 h after the fetch), not 6 h", dueAt in now + 2 * hour - 3 * minute..now + 2 * hour)
        val low = ScreenOnReceiver.screenOnForecastDueAtMs(manager, lat, lon, now, batteryLevel = 60)!!
        assertTrue("below 70 % the 24 h interval holds", low > now + 20 * hour)
    }

    @Test
    fun `the current-temp loop plans a due-time sync only when none is pending`() = kotlinx.coroutines.runBlocking {
        ActiveLocationResolver.persist(context, 37.42, -122.08)
        val repo = io.mockk.mockk<com.weatherwidget.data.repository.WeatherRepository>(relaxed = true)
        io.mockk.coEvery { repo.nextForecastDue(any(), any(), any()) } answers {
            com.weatherwidget.data.remote.ForecastSchedule.Due(WeatherSource.NWS.id, System.currentTimeMillis() + 3 * hour)
        }
        val dao = io.mockk.mockk<com.weatherwidget.data.local.AppLogDao>(relaxed = true)
        val manager = WidgetStateManager(context)

        assertTrue(!WidgetWorkScheduler.dueWakePending(context))
        DueWakePlanner.plan(context, repo, manager, dao, onlyIfMissing = true)
        assertTrue("planned after an install", WidgetWorkScheduler.dueWakePending(context))
        val delay = pending()!!.initialDelayMillis
        assertTrue("≈ 3 h: $delay", delay in 3 * hour - minute..3 * hour + minute)

        DueWakePlanner.plan(context, repo, manager, dao, onlyIfMissing = true)
        io.mockk.coVerify(exactly = 1) { repo.nextForecastDue(any(), any(), any()) }
    }
}
