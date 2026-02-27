package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric test verifying that cycling the API in ForecastHistoryActivity
 * correctly synchronizes the choice back to the widget and triggers a UI update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryActivitySyncRoboTest {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        stateManager = WidgetStateManager(context)
    }

    @Test
    fun `cycling API in activity updates manager and sends broadcast`() {
        val testWidgetId = 101

        // Initial setup: Ensure visible sources exist
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(visibleSources)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        // Launch activity with specific widget ID and history extras
        val intent = Intent(context, ForecastHistoryActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, "2030-01-01")
            putExtra(ForecastHistoryActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastHistoryActivity.EXTRA_LON, -122.0)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }

        // We use buildActivity().get() to get the activity instance
        // ForecastHistoryActivity uses Hilt for @Inject fields, so we must mock them 
        // if we are not running a full Hilt test.
        val controller = Robolectric.buildActivity(ForecastHistoryActivity::class.java, intent)
        val activity = controller.get()
        
        // Manual injection of required DAOs (mocked) to avoid Hilt @Inject failures during onCreate
        activity.forecastDao = mockk(relaxed = true)
        
        controller.setup()

        // Track broadcasts
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())

        // Find and click the API source button
        val apiButton = activity.findViewById<View>(R.id.api_source_button)
        apiButton.performClick()

        // 1. Verify manager was updated (NWS -> OPEN_METEO)
        assertEquals(
            "Manager source should have updated to the next visible source",
            WeatherSource.OPEN_METEO,
            stateManager.getCurrentDisplaySource(testWidgetId)
        )

        // 2. Verify broadcast was sent back to widget
        val broadcastIntents = shadowApp.broadcastIntents
        val refreshBroadcast = broadcastIntents.find {
            it.action == WeatherWidgetProvider.ACTION_REFRESH &&
            it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) == testWidgetId
        }

        assertNotNull("Should have sent REFRESH broadcast back to widget", refreshBroadcast)
        assertTrue("Broadcast should be UI_ONLY to avoid extra network calls", refreshBroadcast!!.getBooleanExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, false))
    }
}
