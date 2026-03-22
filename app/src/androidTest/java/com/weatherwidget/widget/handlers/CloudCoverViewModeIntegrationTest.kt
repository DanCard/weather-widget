package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Integration tests for the CLOUD_COVER view mode:
 * - handleSetView transitions and state persistence
 * - Navigation routing in graph mode
 * - API toggle preserves CLOUD_COVER mode
 * - toggleCloudCoverMode cycles correctly
 */
@RunWith(AndroidJUnit4::class)
class CloudCoverViewModeIntegrationTest : IsolatedIntegrationTest("cloud_cover_view_mode") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 55

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        insertBaseHourlyData()
    }

    // -------------------------------------------------------------------------
    // handleSetView — state transitions
    // -------------------------------------------------------------------------

    @Test
    fun handleSetView_cloudCover_setsViewMode() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER)

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_temperatureFromCloudCover_returnsToTemperature() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.TEMPERATURE)

        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_cloudCover_preservesHourlyOffset() = runBlocking {
        stateManager.setHourlyOffset(testWidgetId, 4)

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER, targetOffset = 4)

        assertEquals(4, stateManager.getHourlyOffset(testWidgetId))
        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_cloudCoverWithExplicitOffset_appliesOffset() = runBlocking {
        stateManager.setHourlyOffset(testWidgetId, 0)

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER, targetOffset = 6)

        assertEquals(6, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleSetView_daily_resetsZoomToWide() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setZoomLevel(testWidgetId, ZoomLevel.NARROW)

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.DAILY)

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    // -------------------------------------------------------------------------
    // handleNavigation routes to hourly graph navigation in CLOUD_COVER mode
    // -------------------------------------------------------------------------

    @Test
    fun handleNavigation_inCloudCoverMode_navigatesHourlyNotDaily() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setHourlyOffset(testWidgetId, 0)

        // Navigate right — in hourly modes this increments the hourly offset
        WidgetIntentRouter.handleNavigation(context, testWidgetId, isLeft = false)

        // View mode must still be CLOUD_COVER (not flipped to DAILY)
        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        // Hourly offset must have changed (graph panned right)
        val newOffset = stateManager.getHourlyOffset(testWidgetId)
        assertTrue("Offset should have moved right from 0, got $newOffset", newOffset > 0)
    }

    @Test
    fun handleNavigation_left_inCloudCoverMode_navigatesHourlyNotDaily() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setHourlyOffset(testWidgetId, 4)

        WidgetIntentRouter.handleNavigation(context, testWidgetId, isLeft = true)

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        val newOffset = stateManager.getHourlyOffset(testWidgetId)
        assertTrue("Offset should have moved left from 4, got $newOffset", newOffset < 4)
    }

    // -------------------------------------------------------------------------
    // handleToggleApi preserves CLOUD_COVER mode
    // -------------------------------------------------------------------------

    @Test
    fun handleToggleApi_inCloudCoverMode_preservesViewMode() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        val initialSource = stateManager.getCurrentDisplaySource(testWidgetId)

        WidgetIntentRouter.handleToggleApi(context, testWidgetId)

        assertEquals(
            "View mode must remain CLOUD_COVER after API toggle",
            ViewMode.CLOUD_COVER,
            stateManager.getViewMode(testWidgetId),
        )
        val newSource = stateManager.getCurrentDisplaySource(testWidgetId)
        assertTrue(
            "Source must change after toggle: was $initialSource, still $initialSource",
            newSource != initialSource,
        )
    }

    @Test
    fun handleSetView_cloudCoverFallsBackToSourceWithVisibleCloudData() = runBlocking {
        insertHourlyRows(
            source = WeatherSource.SILURIAN,
            cloudCoverProvider = { null },
            condition = "Mostly Clear",
        )
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.SILURIAN, WeatherSource.NWS))
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.SILURIAN)
        stateManager.setHourlyOffset(testWidgetId, 0)

        val now = LocalDateTime.now()
        val hourlyStart = now.minusHours(8).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hourlyEnd = now.plusHours(16).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(hourlyStart, hourlyEnd, 37.42, -122.08)

        val resolvedSource = CloudCoverViewHandler.selectCloudCoverSource(
            hourlyForecasts = hourlyForecasts,
            requestedSource = WeatherSource.SILURIAN,
            centerTime = now,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(
            "Cloud cover mode should render from a source that has cloud data in the visible window",
            WeatherSource.NWS,
            resolvedSource,
        )

        WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER)

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        assertEquals(
            "Render fallback must not mutate the widget's selected API",
            WeatherSource.SILURIAN,
            stateManager.getCurrentDisplaySource(testWidgetId),
        )
    }

    // -------------------------------------------------------------------------
    // toggleCloudCoverMode state machine
    // -------------------------------------------------------------------------

    @Test
    fun toggleCloudCoverMode_fromTemperature_switchesToCloudCover() {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)

        val newMode = stateManager.toggleCloudCoverMode(testWidgetId)

        assertEquals(ViewMode.CLOUD_COVER, newMode)
        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun toggleCloudCoverMode_fromCloudCover_switchesToTemperature() {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)

        val newMode = stateManager.toggleCloudCoverMode(testWidgetId)

        assertEquals(ViewMode.TEMPERATURE, newMode)
        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun toggleCloudCoverMode_fromPrecipitation_switchesToCloudCover() {
        stateManager.setViewMode(testWidgetId, ViewMode.PRECIPITATION)

        val newMode = stateManager.toggleCloudCoverMode(testWidgetId)

        assertEquals(ViewMode.CLOUD_COVER, newMode)
    }

    @Test
    fun toggleCloudCoverMode_preservesZoomLevel() {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.setZoomLevel(testWidgetId, ZoomLevel.NARROW)

        stateManager.toggleCloudCoverMode(testWidgetId)

        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun toggleCloudCoverMode_preservesHourlyOffset() {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.setHourlyOffset(testWidgetId, 8)

        stateManager.toggleCloudCoverMode(testWidgetId)

        assertEquals(8, stateManager.getHourlyOffset(testWidgetId))
    }

    // -------------------------------------------------------------------------
    // handleResize — CLOUD_COVER uses graph path (not daily)
    // -------------------------------------------------------------------------

    @Test
    fun handleResize_inCloudCoverMode_doesNotCrash() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)

        // Should not throw; if it crashes the test fails
        WidgetIntentRouter.handleResize(context, testWidgetId)

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    private fun insertBaseHourlyData() = runBlocking {
        insertHourlyRows(
            source = WeatherSource.NWS,
            cloudCoverProvider = { h -> (30 + h * 2).coerceIn(0, 100) },
            condition = "Partly Cloudy",
        )
    }

    private fun insertHourlyRows(
        source: WeatherSource,
        cloudCoverProvider: (Int) -> Int?,
        condition: String,
    ) = runBlocking {
        val now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourlyDao = db.hourlyForecastDao()
        val forecasts = (-6..30).map { h ->
            val dt = now.plusHours(h.toLong())
            val key = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            HourlyForecastEntity(
                dateTime = key,
                locationLat = 37.42,
                locationLon = -122.08,
                temperature = 60f + h,
                condition = condition,
                source = source.id,
                precipProbability = 20,
                cloudCover = cloudCoverProvider(h),
                fetchedAt = System.currentTimeMillis(),
            )
        }
        hourlyDao.insertAll(forecasts)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
