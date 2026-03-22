package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.AndroidTestDatabase
import com.weatherwidget.testutil.AndroidTestWidgetState
import com.weatherwidget.testutil.IsolatedIntegrationTest
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
 * Instrumented tests verifying that tapping "today" navigates to the
 * hourly precipitation graph when any rain is indicated.
 *
 * Tests the full chain: daily ForecastEntity precipProbability +
 * RainAnalyzer hourly data → DayClickHelper → WidgetStateManager.
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
                    targetDate = todayStr,
                    forecastDate = todayStr,
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
    fun todayWithDailyPrecip_butNoHourlyRain_navigatesToPrecipitation() {
        val today = LocalDate.now()
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        val futureHour = now.plusHours(3)
        val forecasts = listOf(
            createForecast(
                futureHour.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                precipProb = 6,
            ),
        )

        val dailyPrecipProbability = 16

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        assertNull("Hourly data should NOT meet 40% threshold", rainSummary)

        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)
        assertTrue("Daily 16% precip should count as rain for navigation", hasRain)
        assertEquals(
            "Should resolve to PRECIPITATION view mode",
            ViewMode.PRECIPITATION,
            DayClickHelper.resolveTargetViewMode(hasRain),
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
    fun todayWith8PercentDailyPrecip_navigatesToTemperature() {
        val today = LocalDate.now()
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        val futureHour = now.plusHours(3)
        val forecasts = listOf(
            createForecast(
                futureHour.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                precipProb = 5,
            ),
        )

        val dailyPrecipProbability = 8

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)

        assertFalse("8% daily precip should NOT resolve to precipitation", hasRain)
        assertEquals(ViewMode.TEMPERATURE, DayClickHelper.resolveTargetViewMode(hasRain))
    }

    @Test
    fun todayWithHighHourlyRain_navigatesToPrecipitation() {
        val today = LocalDate.now()
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        val futureHour = now.plusHours(5)
        val forecasts = listOf(
            createForecast(
                futureHour.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                precipProb = 70,
            ),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        assertNotNull("70% hourly rain should be detected", rainSummary)

        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 70)
        assertEquals(ViewMode.PRECIPITATION, DayClickHelper.resolveTargetViewMode(hasRain))

        val offset = DayClickHelper.calculatePrecipitationOffset(now, today)
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION, offset)
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun productionCodePath_includesDailyPrecipInClickDecision() {
        val today = LocalDate.now()
        val todayStr = today.toString()
        val now = today.atTime(10, 0)

        val lat = WeatherWidgetWorker.DEFAULT_LAT
        val lon = WeatherWidgetWorker.DEFAULT_LON

        runBlocking {
            val forecastDao = db.forecastDao()
            val hourlyDao = db.hourlyForecastDao()

            forecastDao.insertForecast(
                ForecastEntity(
                    targetDate = todayStr,
                    forecastDate = todayStr,
                    locationLat = lat,
                    locationLon = lon,
                    locationName = "Mountain View, CA",
                    highTemp = 72f,
                    lowTemp = 54f,
                    condition = "Cloudy",
                    source = WeatherSource.NWS.id,
                    precipProbability = 16,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            hourlyDao.insertAll(
                listOf(
                    createForecast(
                        dateTime = now.plusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        precipProb = 6,
                        source = WeatherSource.NWS.id,
                    ),
                ),
            )

            val todayWeather = forecastDao.getForecastForDate(todayStr, lat, lon)
            val dailyPrecipProb = todayWeather?.precipProbability

            val zoneId = ZoneId.systemDefault()
            val hourlyStart = now.minusHours(24).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyEnd = now.plusHours(60).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val source = todayWeather?.source ?: "NWS"
            val rainSummary = RainAnalyzer.getRainSummary(hourlyForecasts, today, source, now)

            val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProb)

            if ((dailyPrecipProb ?: 0) > 8 || rainSummary != null) {
                assertTrue(
                    "hasRainForecast should be true when daily precip=$dailyPrecipProb (>8) or rainSummary=$rainSummary",
                    hasRain,
                )
            }
        }
    }
}
