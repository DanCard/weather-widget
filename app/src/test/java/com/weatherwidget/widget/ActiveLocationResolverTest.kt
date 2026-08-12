package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppWidgetManager

@Category(LongDuration::class)
class ActiveLocationResolverTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private lateinit var forecastDao: ForecastDao
    private lateinit var shadowAppWidgetManager: ShadowAppWidgetManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ActiveLocationResolver.clearForTesting(context)
        stateManager = WidgetStateManager(context)
        forecastDao = mockk(relaxed = true)
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        shadowAppWidgetManager = shadowOf(appWidgetManager)
    }

    /**
     * The whole point of the change: this used to answer Google HQ, so the worker fetched and
     * labelled Mountain View's weather for a user who had no location at all.
     */
    @Test
    fun `resolve returns null when no widgets and no weather data exist`() = runTest {
        coEvery { forecastDao.getLatestWeather() } returns null

        assertNull(ActiveLocationResolver.resolve(context, stateManager, forecastDao))
    }

    @Test
    fun `resolve persists nothing when there is no location to resolve`() = runTest {
        coEvery { forecastDao.getLatestWeather() } returns null

        ActiveLocationResolver.resolve(context, stateManager, forecastDao)

        assertNull(
            "the one-time migration must never write a placeholder coordinate",
            ActiveLocationResolver.current(context),
        )
    }

    @Test
    fun `resolve uses latest weather coordinates when no widgets exist but weather database has data`() = runTest {
        val mockWeather = mockk<ForecastEntity>()
        coEvery { mockWeather.locationLat } returns 40.7128
        coEvery { mockWeather.locationLon } returns -74.0060
        coEvery { forecastDao.getLatestWeather() } returns mockWeather

        val result = ActiveLocationResolver.resolve(context, stateManager, forecastDao)!!
        assertEquals(40.7128, result.first, 0.0001)
        assertEquals(-74.0060, result.second, 0.0001)
    }

    @Test
    fun `resolve prioritizes widget configured coordinates over database and default`() = runTest {
        // Add a fake widget ID
        val widgetId = 101
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        // Set configured location in shared preferences
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 34.0522f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -118.2437f)
            .commit()

        val mockWeather = mockk<ForecastEntity>()
        coEvery { mockWeather.locationLat } returns 40.7128
        coEvery { mockWeather.locationLon } returns -74.0060
        coEvery { forecastDao.getLatestWeather() } returns mockWeather

        val result = ActiveLocationResolver.resolve(context, stateManager, forecastDao)!!
        assertEquals(34.0522, result.first, 0.001)
        assertEquals(-118.2437, result.second, 0.001)
    }

    @Test
    fun `canonical location wins and heals divergent widget compatibility copies`() = runTest {
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(101, info)
        shadowAppWidgetManager.addBoundWidget(202, info)
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}101", 47.6062f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}101", -122.3321f)
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}202", 40.7128f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}202", -74.0060f)
            .commit()
        ActiveLocationResolver.persist(context, 37.4219, -122.0840)

        val result = ActiveLocationResolver.resolve(context, stateManager, forecastDao)!!

        assertEquals(37.4219, result.first, 0.001)
        assertEquals(-122.0840, result.second, 0.001)
        for (id in intArrayOf(101, 202)) {
            assertEquals(
                37.4219f,
                prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN),
                0.0001f,
            )
            assertEquals(
                -122.0840f,
                prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN),
                0.0001f,
            )
        }
    }
}
