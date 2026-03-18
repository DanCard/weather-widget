package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureActualsIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 42

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------
    // Zoom reset tests — pure WidgetStateManager, no network needed
    // -------------------------------------------------------------------

    // handleSetView also attempts a widget RemoteViews update which requires a real registered
    // widget. We absorb that NPE with runCatching — zoom state is persisted to SharedPreferences
    // before the update fires, so asserts are valid regardless.

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering TEMPERATURE`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.TEMPERATURE)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering PRECIPITATION`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.PRECIPITATION)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering CLOUD_COVER`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.CLOUD_COVER)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView preserves NARROW zoom when switching between hourly view types`() = runTest {
        // Start in TEMPERATURE at NARROW zoom
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        // Switch to PRECIPITATION — should NOT reset zoom
        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.PRECIPITATION)
        }

        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(appWidgetId))
    }

}

