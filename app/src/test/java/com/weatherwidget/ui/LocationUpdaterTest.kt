package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppWidgetManager

@Category(LongDuration::class)
class LocationUpdaterTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var shadowAppWidgetManager: ShadowAppWidgetManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val appWidgetManager = AppWidgetManager.getInstance(context)
        shadowAppWidgetManager = shadowOf(appWidgetManager)

        // Clear prefs before each test
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit().clear().commit()
    }

    @Test
    fun `shouldHealTo returns false when no widgets are placed`() {
        val result = LocationUpdater.shouldHealTo(context, 37.4220, -122.0841)
        assertFalse(result)
    }

    @Test
    fun `shouldHealTo returns true when widgets are at default coordinates and fresh coordinates are different`() {
        val widgetId = 201
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", WeatherWidgetWorker.DEFAULT_LAT.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", WeatherWidgetWorker.DEFAULT_LON.toFloat())
            .commit()

        // 40.7128 is not sameSite with 37.4220
        val result = LocationUpdater.shouldHealTo(context, 40.7128, -74.0060)
        assertTrue(result)
    }

    @Test
    fun `shouldHealTo returns false when widgets are already sameSite with fresh coordinates`() {
        val widgetId = 202
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 37.4220f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -122.0841f)
            .commit()

        // 37.4221 is sameSite with 37.4220 (difference < 0.002)
        val result = LocationUpdater.shouldHealTo(context, 37.4221, -122.0840)
        assertFalse(result)
    }

    @Test
    fun `shouldHealTo returns true when widgets are at stale coordinates and fresh coordinates are different`() {
        val widgetId = 203
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 34.0522f) // Los Angeles
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -118.2437f)
            .commit()

        // Fresh coordinate is Googleplex (different site)
        val result = LocationUpdater.shouldHealTo(context, 37.4220, -122.0841)
        assertTrue(result)
    }
}
