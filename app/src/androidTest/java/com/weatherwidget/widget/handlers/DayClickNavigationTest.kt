package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.AndroidTestDatabase
import com.weatherwidget.testutil.AndroidTestWidgetState
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.testutil.dateEpoch
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Instrumented tests verifying icon-home routing to hourly graph modes.
 */
@RunWith(AndroidJUnit4::class)
class DayClickNavigationTest : IsolatedIntegrationTest("day_click_navigation") {

    private lateinit var stateManager: WidgetStateManager

    private val testWidgetId = 99990

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        runBlocking {
            val todayStr = LocalDate.now().toString()
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = dateEpoch(todayStr),
                    forecastDate = dateEpoch(todayStr),
                    locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                    locationLon = WeatherWidgetWorker.DEFAULT_LON,
                    locationName = "Mountain View, CA",
                    highTemp = 72f,
                    lowTemp = 54f,
                    condition = "Cloudy",
                    source = WeatherSource.NWS.id,
                    precipProbability = 0,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    private fun createForecast(
        dateTime: Long,
        precipProb: Int? = 0,
        source: String = "NWS",
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.7749,
            locationLon = -122.4194,
            temperature = 70f,
            condition = if ((precipProb ?: 0) >= 40) "Rain" else "Clear",
            source = source,
            precipProbability = precipProb,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    @Test
    fun rainyIcon_navigatesToPrecipitation() {
        val today = LocalDate.now()
        val now = today.atTime(10, 0)
        assertEquals(
            "Should resolve to PRECIPITATION view mode",
            ViewMode.PRECIPITATION,
            DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_rain),
        )

        val offset = DayClickHelper.calculatePrecipitationOffset(now, today)
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION, offset)
            } catch (_: Exception) {}
        }

        assertEquals(
            "View mode should be PRECIPITATION",
            ViewMode.PRECIPITATION,
            stateManager.getViewMode(testWidgetId),
        )
    }

    @Test
    fun cloudEligibleIcon_navigatesToCloudCover() {
        assertEquals(ViewMode.CLOUD_COVER, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_mostly_clear))
    }

    @Test
    fun clearIcon_navigatesToTemperature() {
        assertEquals(ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_clear))
    }
}
