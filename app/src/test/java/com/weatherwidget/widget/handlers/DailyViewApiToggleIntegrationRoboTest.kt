package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyViewApiToggleIntegrationRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 42

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        RefreshScheduler.setIsRefreshDisabledForTesting(true)

        // KEEP THIS. handleToggleApi wraps handleToggleApiInternal in its own catch + Log.e, so a
        // failure inside the handler never reaches this test's catch — it only shows up in logcat.
        // Robolectric drops Log output unless ShadowLog.stream is set, which makes those failures
        // completely invisible and any assertion here look mysteriously wrong.
        //
        // This is exactly how the enqueue-ordering bug was found: the toggle was silently dying on
        // "NullPointerException: Cannot read field \"layoutId\" because \"widgetInfo\" is null" from
        // refreshDailyView (widget 42 is not host-bound here), so the forced refresh that used to
        // sit after the repaint was never reached. Without this line the test just said "no refresh
        // enqueued" with no hint why.
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun dailyForecast_apiToggle_cyclesThroughSources_preservesDailyMode() = runBlocking {
        val sources = stateManager.getEffectiveVisibleSourcesOrder(testWidgetId)

        assertEquals("Should start with NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("Should start in DAILY mode", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 1st toggle, should be OPEN_METEO", WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 1st toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 2nd toggle, should be WEATHER_API", WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 2nd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 3rd toggle, should return to NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 3rd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
    }

    /**
     * The test DB has no rows for any source, so every toggle trips the "missing data" arm of
     * sourceNeedsRefresh. What matters here is that the resulting forced refresh is scoped to the
     * source just switched to — an untargeted refresh force-fetches every enabled provider and
     * burns quota on the key-based ones. The staleness arm is covered by SourceNeedsRefreshTest.
     */
    @Test
    fun apiToggle_forcedRefreshTargetsOnlyTheNewlySelectedSource() = runBlocking {
        val expectedAfterEachToggle = listOf(WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API, WeatherSource.NWS)

        expectedAfterEachToggle.forEachIndexed { index, expectedSource ->
            RefreshScheduler.lastForcedRefreshForTesting = null
            try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}

            val request = RefreshScheduler.lastForcedRefreshForTesting
            assertNotNull("Toggle ${index + 1} should enqueue a forced refresh (DB has no data)", request)
            assertEquals(
                "Toggle ${index + 1} should target only ${expectedSource.id}",
                expectedSource.id,
                request!!.targetSourceId,
            )
            assertEquals("Toggle ${index + 1} should be attributed to the toggle path", "toggle_api_stale", request.reason)
        }
    }
}