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
 * from a background app without notification permission) saying what it queued, or why nothing.
 * Only a refresh request shows one: browsing taps and source changes say nothing at all, whatever
 * they queued. plans/261015-refresh-queued-toast-all-surfaces.md,
 * plans/261016-refresh-notice-only-on-refresh-requests.md
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
        context.getSharedPreferences("weather_fetch_metadata", Context.MODE_PRIVATE).edit().clear().commit()
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
                    fetchedAt = now, lastConfirmedAt = now,
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

    private fun oneTimeSyncTags(): List<Set<String>> =
        androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(com.weatherwidget.widget.testScopedWorkName(com.weatherwidget.widget.WidgetWorkScheduler.WORK_NAME_ONE_TIME))
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
            .map { it.tags }

    // A tap that starts a refresh says so on its widget (user, 2026-11-01); one that starts none, such
    // as a source change onto fresh data, shows nothing.

    @Test
    fun `toggle onto a source with no data queues a refresh and says so`() = runBlocking {
        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        assertEquals("rows=${rows()}", "Meteo · Forecast and observations refresh queued", banner())
        assertEquals(listOf("origin=widget_toggle_api queued=BOTH source=Meteo shown banner=1"), rows())
    }

    @Test
    fun `toggle onto a fresh source shows nothing`() = runBlocking {
        seedFresh(WeatherSource.OPEN_METEO)
        mockkObject(GraphInteractionRenderer)
        coEvery { GraphInteractionRenderer.selectedSourceNeedsRefresh(any(), any(), any(), any(), any(), any()) } returns false

        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        assertEquals(null, banner())
        assertTrue(rows().toString(), rows().all { it.startsWith("origin=widget_toggle_api ") && it.endsWith(" silent") })
    }

    @Test
    fun `toggle onto a quota-blocked source shows nothing`() = runBlocking {
        val until = System.currentTimeMillis() + 3_600_000L
        com.weatherwidget.data.model.ForecastProduct.entries.forEach {
            SourceQuotaBlocks.block(WeatherSource.OPEN_METEO.id, it, until)
        }
        mockkObject(GraphInteractionRenderer)
        coEvery { GraphInteractionRenderer.selectedSourceNeedsRefresh(any(), any(), any(), any(), any(), any()) } returns false

        try { WidgetIntentRouter.handleToggleApi(context, widgetId) } catch (_: Exception) {}

        assertEquals(null, banner())
        assertTrue(rows().toString(), rows().single().contains("none=QUOTA") && rows().single().endsWith(" silent"))
    }

    @Test
    fun `home button back to a fresh primary source shows nothing`() = runBlocking {
        // The Pixel, 2026-10-15: "NWS forecast up to date · 85m ago" after the home tap.
        seedFresh(WeatherSource.NWS)
        stateManager.markLongFetch(WeatherSource.NWS.id, lat, lon, System.currentTimeMillis())
        // seedFresh's single row is incomplete to the real probe, which rightly fetches it.
        mockkObject(GraphInteractionRenderer)
        coEvery { GraphInteractionRenderer.selectedSourceNeedsRefresh(any(), any(), any(), any(), any(), any()) } returns false
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)

        try { WidgetIntentRouter.handleResetSource(context, widgetId) } catch (_: Exception) {}

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(widgetId))
        assertEquals("rows=${rows()}", null, banner())
        assertTrue(rows().toString(), rows().all { it.startsWith("origin=widget_reset_source ") && it.endsWith(" silent") })
    }

    @Test
    fun `home tap during another source's sync - its refresh queues behind it instead of being dropped`() = runBlocking {
        // The Samsung, 2026-10-15: toggle to a stale Open-Meteo, home back to a stale NWS 3 s later;
        // the NWS request joined the Open-Meteo-only sync under KEEP and NWS was never fetched. The
        // Open-Meteo sync is held by a delay to stay pending (undelayed work runs at once here).
        com.weatherwidget.widget.WidgetWorkScheduler.enqueueForcedSync(
            context, "toggle_api_stale", androidx.work.ExistingWorkPolicy.KEEP,
            initialDelayMs = 60_000, targetSourceId = WeatherSource.OPEN_METEO.id,
        )
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)

        try { WidgetIntentRouter.handleResetSource(context, widgetId) } catch (_: Exception) {}

        val targets = oneTimeSyncTags().map { tags -> tags.single { it.startsWith("forced_target:") } }
        assertEquals(
            setOf("forced_target:${WeatherSource.OPEN_METEO.id}", "forced_target:${WeatherSource.NWS.id}"),
            targets.toSet(),
        )
        assertEquals(2, targets.size)
        assertEquals("rows=${rows()}", "NWS · Forecast and observations refresh queued", banner())
        assertEquals(listOf("origin=widget_reset_source queued=BOTH source=NWS shown banner=1"), rows())
    }

    @Test
    fun `zoom on fresh data shows nothing`() = runBlocking {
        seedFresh(WeatherSource.NWS)
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)

        try { WidgetIntentRouter.handleCycleZoom(context, widgetId) } catch (_: Exception) {}

        assertEquals(null, banner())
        assertEquals(emptyList<String>(), rows())
    }

    // Browsing is looking, not asking for data (user, 2026-10-16: an arrow tap on 240-min-old NWS
    // showed "Forecast and observations refresh queued"). The refresh happens; the row records it.

    @Test
    fun `zoom on stale data queues a refresh and says so`() = runBlocking {
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)

        try { WidgetIntentRouter.handleCycleZoom(context, widgetId) } catch (_: Exception) {}

        assertEquals("rows=${rows()}", "NWS · Forecast and observations refresh queued", banner())
        assertEquals(listOf("origin=widget_zoom queued=BOTH source=NWS shown banner=1"), rows())
    }

    @Test
    fun `daily arrow on stale data queues a refresh and says so`() = runBlocking {
        try { WidgetIntentRouter.handleNavigation(context, widgetId, isLeft = false) } catch (_: Exception) {}

        assertEquals("rows=${rows()}", "NWS · Forecast and observations refresh queued", banner())
        assertEquals(listOf("origin=widget_nav queued=BOTH source=NWS shown banner=1"), rows())
    }

    @Test
    fun `view switch on stale data queues a refresh and says so`() = runBlocking {
        try { WidgetIntentRouter.handleToggleView(context, widgetId) } catch (_: Exception) {}

        assertEquals("rows=${rows()}", "NWS · Forecast and observations refresh queued", banner())
        assertTrue(rows().toString(), rows().isNotEmpty() && rows().all { it.endsWith(" shown banner=1") })
    }

    private fun mockStaleTapToRefresh() {
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns null
        coEvery { DataFreshness.isDataStale(any()) } returns true
    }

    @Test
    fun `tap to refresh queues the forecast on first tap, throttles second tap within 10 minutes, ui-only repaints say nothing`() = runBlocking {
        val upToDate = RefreshNotice.NotQueued(RefreshNotice.Reason.UP_TO_DATE, "NWS", ageMs = 10 * 60_000L)
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns upToDate
        coEvery { DataFreshness.isDataStale(any()) } returns false
        val repository = mockk<WeatherRepository>(relaxed = true)

        WidgetRefreshCoordinator.refresh(context, uiOnly = true, repository, widgetId)
        assertEquals(null, banner())

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("NWS · Forecast refresh queued", banner())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))
        assertEquals(null, banner())

        RefreshToast.resetForTesting()
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("Not doing because last refresh was 0 minutes ago, and wait another 10 minutes before asking for a refresh", banner())
    }

    @Test
    fun `tap to refresh throttling is isolated per display source`() = runBlocking {
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.NWS)

        val upToDate = RefreshNotice.NotQueued(RefreshNotice.Reason.UP_TO_DATE, "NWS", ageMs = 10 * 60_000L)
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns upToDate
        coEvery { DataFreshness.isDataStale(any()) } returns false
        val repository = mockk<WeatherRepository>(relaxed = true)

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("NWS · Forecast refresh queued", banner())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))
        RefreshToast.resetForTesting()

        // Second tap on NWS is throttled
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("Not doing because last refresh was 0 minutes ago, and wait another 10 minutes before asking for a refresh", banner())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))
        RefreshToast.resetForTesting()

        // Switching widget to OPEN_METEO allows refresh immediately
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)
        assertEquals("Meteo · Forecast refresh queued", banner())
    }

    @Test
    fun `tap to refresh on stale data queues the forecast`() = runBlocking {
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns null
        coEvery { DataFreshness.isDataStale(any()) } returns true

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)

        assertEquals("NWS · Forecast refresh queued", banner())
    }

    private fun viewingRows() = runBlocking {
        db.appLogDao().getLogsByTag(com.weatherwidget.shared.util.ViewingForecastRefresh.LOG_TAG, 5).map { it.message }
    }

    /** A bound widget, so the paint runs, showing NWS, 3 h old at 85 %: past the limit a tap holds it to. */
    private fun boundWidgetOnStaleSource(): Int {
        val id = shadowOf(android.appwidget.AppWidgetManager.getInstance(context))
            .createWidget(com.weatherwidget.widget.WeatherWidgetProvider::class.java, com.weatherwidget.R.layout.widget_weather)
        stateManager.setViewMode(id, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(id, WeatherSource.NWS)
        seedFresh(WeatherSource.NWS)
        stateManager.markLongFetch(WeatherSource.NWS.id, lat, lon, System.currentTimeMillis() - 3 * 60 * 60_000L)
        mockkObject(com.weatherwidget.widget.BatterySnapshotProvider)
        every { com.weatherwidget.widget.BatterySnapshotProvider.snapshot(any()) } returns
            com.weatherwidget.widget.BatterySnapshot(isCharging = false, batteryLevel = 85)
        return id
    }

    @Test
    fun `a repaint nobody tapped for checks nothing, however stale the source`() = runBlocking {
        // Emulator, 2026-11-01: a ui-only ACTION_REFRESH logged trigger=widget_tap, forced a fetch and
        // showed the banner. A screen-on paint-debt repaint takes this path.
        val id = boundWidgetOnStaleSource()

        WidgetIntentRouter.renderWidgetFromCache(context, id)

        assertEquals(emptyList<String>(), viewingRows())
    }

    @Test
    fun `a tap's render checks the displayed source`() = runBlocking {
        val id = boundWidgetOnStaleSource()

        WidgetIntentRouter.handleRecenterToday(context, id)

        assertEquals(viewingRows().toString(), true, viewingRows().single().startsWith("source=NWS trigger=widget_tap"))
    }

    @Test
    fun `tap to refresh shows its notice before the repaint starts`() = runBlocking {
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { DataFreshness.isDataStale(any()) } returns false
        var bannerAtRepaint: String? = null
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } answers {
            bannerAtRepaint = stateManager.getActiveTransientMessage(widgetId)
            null
        }

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)

        assertEquals("NWS · Forecast refresh queued", bannerAtRepaint)
    }

    @Test
    fun `a rate-limited tap whose repaint queues a refresh says so`() = runBlocking {
        val repository = mockk<WeatherRepository>(relaxed = true)
        mockkObject(WidgetIntentRouter, DataFreshness)
        coEvery { DataFreshness.isDataStale(any()) } returns false
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns null
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))
        RefreshToast.resetForTesting()
        val queued = RefreshNotice.Queued(RefreshNotice.Kind.BOTH, "NWS")
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } answers {
            // The repaint outlasted the rate-limit notice's window, as it does on a slow device.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))
            RefreshToast.resetForTesting()
            queued
        }
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, repository, widgetId)

        assertEquals("NWS · Forecast and observations refresh queued", banner())
    }

    @Test
    fun `tap to refresh on a widget forces that widget's source, so both messages name it`() = runBlocking {
        mockStaleTapToRefresh()
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)

        assertEquals("Meteo · Forecast refresh queued", banner())
        // Undelayed work runs at once here, so finished work counts.
        val tags = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(com.weatherwidget.widget.testScopedWorkName(com.weatherwidget.widget.WidgetWorkScheduler.WORK_NAME_ONE_TIME))
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
            .flatMap { it.tags }
        assertTrue(tags.toString(), com.weatherwidget.widget.WidgetWorkScheduler.forcedTargetTag(WeatherSource.OPEN_METEO.id) in tags)
    }

    @Test
    fun `tap to refresh with no widget id forces the primary source alone`() = runBlocking {
        mockStaleTapToRefresh()
        coEvery { WidgetIntentRouter.renderAllWidgetsFromCache(any(), any()) } returns null

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), requestedWidgetId = null)

        val tags = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(com.weatherwidget.widget.testScopedWorkName(com.weatherwidget.widget.WidgetWorkScheduler.WORK_NAME_ONE_TIME))
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
            .flatMap { it.tags }
        val primary = stateManager.getPrimarySource().id
        assertTrue(tags.toString(), com.weatherwidget.widget.WidgetWorkScheduler.forcedTargetTag(primary) in tags)
        assertTrue(tags.toString(), com.weatherwidget.widget.WidgetWorkScheduler.forcedTargetTag(null) !in tags)
    }

    @Test
    fun `the notice never covers another message already on the widget`() = runBlocking {
        mockStaleTapToRefresh()
        stateManager.setTransientMessage(widgetId, "Getting weather for Mountain View…", System.currentTimeMillis() + 60_000L)

        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)

        assertEquals("Getting weather for Mountain View…", banner())
        assertEquals(listOf("origin=widget_tap_to_refresh queued=FORECAST source=NWS suppressed busy"), rows())
    }

    @Test
    fun `the banner clears itself after two seconds`() = runBlocking {
        mockStaleTapToRefresh()
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)
        assertEquals("NWS · Forecast refresh queued", banner())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RefreshNotice.DURATION_MS))

        assertEquals(null, banner())
    }

    @Test
    fun `a refresh's ending replaces its queued banner but no other message`() = runBlocking {
        mockStaleTapToRefresh()
        WidgetRefreshCoordinator.refresh(context, uiOnly = false, mockk(relaxed = true), widgetId)
        assertEquals("NWS · Forecast refresh queued", banner())

        val ids = intArrayOf(widgetId)
        RefreshToast.showOutcome(context, "widget_tap_to_refresh", com.weatherwidget.shared.util.RefreshOutcome.Refreshed, "Refreshed NWS", ids)
        assertEquals("Refreshed NWS", banner())

        stateManager.setTransientMessage(widgetId, "Getting weather for Mountain View…", System.currentTimeMillis() + 60_000L)
        RefreshToast.showOutcome(context, "widget_tap_to_refresh", com.weatherwidget.shared.util.RefreshOutcome.Refreshed, "Refreshed NWS", ids)
        assertEquals("Getting weather for Mountain View…", banner())
    }
}
