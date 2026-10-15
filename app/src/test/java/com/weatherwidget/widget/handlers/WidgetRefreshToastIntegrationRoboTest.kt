package com.weatherwidget.widget.handlers

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.DataFreshness
import com.weatherwidget.widget.RefreshToast
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetRefreshCoordinator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real widget tap handlers + RefreshScheduler + RefreshToast + Room + WorkManager (test driver): each
 * tap shows one two-second notice on the tapped widget's banner (never a Toast — Android drops those
 * from a background app without notification permission) saying what it queued, or why nothing; and
 * browsing taps say nothing when nothing was queued. plans/261015-refresh-queued-toast-all-surfaces.md
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetRefreshToastIntegrationRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private val widgetId = 77
    private val lat = 37.4220
    private val lon = -122.0841

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor { _ -> }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        ActiveLocationResolver.persist(context, lat, lon)
        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit().clear().commit()
        SourceQuotaBlocks.reset()
        RefreshToast.resetForTesting()
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        // The handlers catch and log; without this a failure inside them is invisible.
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    @After
    fun teardown() {
        unmockkAll()
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        RefreshToast.resetForTesting()
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun seedFresh(source: WeatherSource) = runBlocking {
        val now = System.currentTimeMillis()
        val todayUtc = LocalDate.now(ZoneId.systemDefault()).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        db.forecastDao().insertAll(
            listOf(
                ForecastEntity(
                    targetDate = todayUtc, dateOfPrediction = todayUtc, locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 50f, condition = "Clear", source = source.id,
                    fetchedAt = now, batchFetchedAt = now,
                ),
            ),
        )
    }

    /** The tapped widget's banner; and no Toast, ever, from a widget tap. */
    private fun banner(): String? {
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("widget taps never toast", 0, ShadowToast.shownToastCount())
        return stateManager.getActiveTransientMessage(widgetId)
    }

    private fun rows() = runBlocking { db.appLogDao().getLogsByTag("REFRESH_TOAST", 20).map { it.message } }

    @Test
    fun `toggle onto a source with no data - one toast, forecast and observations queued`() = runBlocking {
        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        // The stale check queued BOTH and the window check queued FORECAST: merged into one toast.
        assertEquals("Forecast and observations refresh queued", banner())
        assertEquals(listOf("origin=widget_toggle_api queued=BOTH shown banner=1"), rows())
    }

    @Test
    fun `toggle onto a fresh source says nothing - toggling is browsing`() = runBlocking {
        seedFresh(WeatherSource.OPEN_METEO)
        mockkObject(GraphInteractionRenderer)
        coEvery { GraphInteractionRenderer.selectedSourceNeedsRefresh(any(), any(), any(), any(), any(), any()) } returns false

        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        assertEquals(null, banner())
        assertEquals(emptyList<String>(), rows())
    }

    @Test
    fun `toggle onto a quota-blocked source - names the source and the block's end`() = runBlocking {
        // No data at all: only the quota block stops the refresh.
        val until = System.currentTimeMillis() + 3_600_000L
        com.weatherwidget.data.model.ForecastProduct.entries.forEach {
            SourceQuotaBlocks.block(WeatherSource.OPEN_METEO.id, it, until)
        }
        mockkObject(GraphInteractionRenderer)
        coEvery { GraphInteractionRenderer.selectedSourceNeedsRefresh(any(), any(), any(), any(), any(), any()) } returns false

        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        val shown = banner()!!
        assertTrue(shown, shown.startsWith("${WeatherSource.OPEN_METEO.shortDisplayName} limit reached · next refresh after "))
    }

    @Test
    fun `zoom on fresh data shows nothing`() = runBlocking {
        seedFresh(WeatherSource.NWS)
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)

        try { WidgetIntentRouter.handleCycleZoom(context, widgetId) } catch (_: Exception) {}

        assertEquals(null, banner())
        assertEquals(emptyList<String>(), rows())
    }

    @Test
    fun `zoom on stale data says what it queued`() = runBlocking {
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)

        try { WidgetIntentRouter.handleCycleZoom(context, widgetId) } catch (_: Exception) {}

        assertEquals("Forecast and observations refresh queued", banner())
    }

    @Test
    fun `tap to refresh on fresh data says up to date, ui-only repaints say nothing`() = runBlocking {
        val upToDate = RefreshNotice.NotQueued(RefreshNotice.Reason.UP_TO_DATE, "NWS", ageMs = 10 * 60_000L)
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns upToDate
        coEvery { DataFreshness.isDataStale(any()) } returns false
        val repository = mockk<WeatherRepository>(relaxed = true)

        WidgetRefreshCoordinator.refresh(context, uiOnly = true, repository, widgetId)
        assertEquals(null, banner())

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("NWS forecast up to date · 10m ago", banner())
    }

    @Test
    fun `tap to refresh on stale data queues the forecast`() = runBlocking {
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns null
        coEvery { DataFreshness.isDataStale(any()) } returns true

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)

        assertEquals("Forecast refresh queued", banner())
    }

    @Test
    fun `the notice never covers another message already on the widget`() = runBlocking {
        stateManager.setTransientMessage(widgetId, "Getting weather for Mountain View…", System.currentTimeMillis() + 60_000L)

        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        assertEquals("Getting weather for Mountain View…", banner())
        assertEquals(listOf("origin=widget_toggle_api queued=BOTH suppressed busy"), rows())
    }

    @Test
    fun `the banner clears itself after two seconds`() = runBlocking {
        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}
        assertEquals("Forecast and observations refresh queued", banner())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))

        assertEquals(null, banner())
    }
}
