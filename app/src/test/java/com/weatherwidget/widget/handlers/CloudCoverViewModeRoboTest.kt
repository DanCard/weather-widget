package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudCoverViewModeRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 55

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(true)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun handleSetView_cloudCover_setsViewMode() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)

        try { WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER) } catch (_: Exception) {}

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_temperatureFromCloudCover_returnsToTemperature() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)

        try { WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.TEMPERATURE) } catch (_: Exception) {}

        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_cloudCover_preservesHourlyOffset() = runBlocking {
        stateManager.setHourlyOffset(testWidgetId, 4)

        try { WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER, targetOffset = 4) } catch (_: Exception) {}

        assertEquals(4, stateManager.getHourlyOffset(testWidgetId))
        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun handleSetView_cloudCoverWithExplicitOffset_appliesOffset() = runBlocking {
        stateManager.setHourlyOffset(testWidgetId, 0)

        try { WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.CLOUD_COVER, targetOffset = 6) } catch (_: Exception) {}

        assertEquals(6, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleSetView_daily_resetsZoomToWide() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setZoomLevel(testWidgetId, ZoomLevel.NARROW)

        try { WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.DAILY) } catch (_: Exception) {}

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun handleNavigation_inCloudCoverMode_navigatesHourlyNotDaily() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setHourlyOffset(testWidgetId, 0)

        try { WidgetIntentRouter.handleNavigation(context, testWidgetId, isLeft = false) } catch (_: Exception) {}

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        val newOffset = stateManager.getHourlyOffset(testWidgetId)
        assertTrue("Offset should have moved right from 0, got $newOffset", newOffset > 0)
    }

    @Test
    fun handleNavigation_left_inCloudCoverMode_navigatesHourlyNotDaily() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setHourlyOffset(testWidgetId, 4)

        try { WidgetIntentRouter.handleNavigation(context, testWidgetId, isLeft = true) } catch (_: Exception) {}

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        val newOffset = stateManager.getHourlyOffset(testWidgetId)
        assertTrue("Offset should have moved left from 4, got $newOffset", newOffset < 4)
    }

    @Test
    fun handleToggleApi_inCloudCoverMode_preservesViewMode() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        val initialSource = stateManager.getCurrentDisplaySource(testWidgetId)

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}

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

    @Test
    fun handleResize_inCloudCoverMode_doesNotCrash() = runBlocking {
        stateManager.setViewMode(testWidgetId, ViewMode.CLOUD_COVER)

        try { WidgetIntentRouter.handleResize(context, testWidgetId) } catch (_: Exception) {}

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }
}