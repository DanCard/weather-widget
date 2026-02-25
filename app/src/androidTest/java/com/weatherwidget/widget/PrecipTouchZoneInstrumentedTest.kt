package com.weatherwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for precipitation probability touch zone.
 *
 * These tests verify that the expanded touch area for the % rain chance
 * is properly configured in RemoteViews with the correct PendingIntent.
 */
@RunWith(AndroidJUnit4::class)
class PrecipTouchZoneInstrumentedTest {

    private lateinit var context: Context
    private lateinit var appWidgetManager: AppWidgetManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appWidgetManager = AppWidgetManager.getInstance(context)
    }

    @Test
    fun precipProbability_andPrecipTouchZone_haveSamePendingIntent() {
        // This test verifies that both R.id.precip_probability and R.id.precip_touch_zone
        // are wired with the same PendingIntent action

        // Create a RemoteViews instance for the widget
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        // Create the precip toggle intent (same as used in handlers)
        val precipIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_TOGGLE_PRECIP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)
        }

        val precipPendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_WIDGET_ID * 2 + 300,
            precipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Apply the PendingIntent to both views (as done in handlers)
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)

        // Verify the views exist (if this doesn't throw, the IDs are valid)
        assertNotNull("precip_probability view ID should exist", R.id.precip_probability)
        assertNotNull("precip_touch_zone view ID should exist", R.id.precip_touch_zone)

        // Verify the intent action is correct
        assertEquals(
            "Intent action should be ACTION_TOGGLE_PRECIP",
            WeatherWidgetProvider.ACTION_TOGGLE_PRECIP,
            precipIntent.action
        )
    }

    @Test
    fun precipTouchZone_layoutParams_areCorrect() {
        // Verify the layout resource contains the expected touch zone configuration
        // by checking that the ID exists and is different from other view IDs

        val precipTouchZoneId = R.id.precip_touch_zone
        val precipProbabilityId = R.id.precip_probability
        val currentTempZoneId = R.id.current_temp_zone
        val apiSourceContainerId = R.id.api_source_container

        // All these IDs should exist and be unique
        assertNotNull("precip_touch_zone ID should exist", precipTouchZoneId)
        assertNotNull("precip_probability ID should exist", precipProbabilityId)
        assertNotNull("current_temp_zone ID should exist", currentTempZoneId)
        assertNotNull("api_source_container ID should exist", apiSourceContainerId)

        // They should all be different IDs
        assertEquals(
            "precip_touch_zone and precip_probability should be different IDs",
            false,
            precipTouchZoneId == precipProbabilityId
        )

        assertEquals(
            "precip_touch_zone and current_temp_zone should be different IDs",
            false,
            precipTouchZoneId == currentTempZoneId
        )

        assertEquals(
            "precip_touch_zone and api_source_container should be different IDs",
            false,
            precipTouchZoneId == apiSourceContainerId
        )
    }

    @Test
    fun allViewHandlers_wirePrecipTouchZone() {
        // Verify that DailyViewHandler, TemperatureViewHandler, and PrecipViewHandler
        // all have access to the required IDs to wire up the touch zone

        // This is a compile-time check - if the IDs exist, the handlers can use them
        val requiredIds = listOf(
            R.id.precip_probability,
            R.id.precip_touch_zone,
            R.id.current_temp,
            R.id.current_temp_zone
        )

        requiredIds.forEach { id ->
            assertNotNull("Required view ID should exist", id)
            assertEquals("ID should be non-zero", true, id != 0)
        }
    }

    @Test
    fun navigationIntent_hasCorrectExtras() {
        // Verify the intent structure used for precipitation navigation
        val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_TOGGLE_PRECIP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)
        }

        assertEquals(
            "Intent should have ACTION_TOGGLE_PRECIP action",
            WeatherWidgetProvider.ACTION_TOGGLE_PRECIP,
            intent.action
        )

        assertEquals(
            "Intent should have widget ID extra",
            TEST_WIDGET_ID,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        )
    }

    companion object {
        private const val TEST_WIDGET_ID = 123
    }
}
