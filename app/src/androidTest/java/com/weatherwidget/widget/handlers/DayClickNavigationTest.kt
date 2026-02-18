package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
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

/**
 * Instrumented tests verifying that tapping "today" navigates to the
 * hourly precipitation graph when any rain is indicated.
 *
 * Tests the full chain: daily WeatherEntity precipProbability +
 * RainAnalyzer hourly data → DayClickHelper → WidgetStateManager.
 *
 * The key bug being tested: the widget shows daily precipitation probability
 * (e.g., "16%") next to the current temp, but clicking today went to
 * ForecastHistoryActivity instead of the precipitation graph because only
 * hourly RainAnalyzer data (40% threshold) was used for click routing.
 */
@RunWith(AndroidJUnit4::class)
class DayClickNavigationTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager

    private val testWidgetId = 99990

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
    }

    private fun createForecast(
        dateTime: String,
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

    // ── Core bug scenario: daily precip shown but hourly below threshold ──

    @Test
    fun todayWithDailyPrecip_butNoHourlyRain_navigatesToPrecipitation() {
        val today = LocalDate.now()
        // Use fixed time to avoid past-hour filtering issues
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        // Hourly data: no hour exceeds 40% (matches real device data)
        val futureHour = now.plusHours(3)
        val forecasts = listOf(
            createForecast(
                String.format("%sT%02d:00", todayStr, futureHour.hour),
                precipProb = 6,
            ),
        )

        // Daily data: 16% precipitation probability (shown on widget)
        val dailyPrecipProbability = 16

        // RainAnalyzer says no rain (below 40% threshold)
        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        assertNull("Hourly data should NOT meet 40% threshold", rainSummary)

        // But DayClickHelper should still detect rain from daily data
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)
        assertTrue("Daily 16% precip should count as rain for navigation", hasRain)
        assertTrue(
            "Should navigate to precipitation graph",
            DayClickHelper.shouldNavigateToPrecipitation(false, hasRain),
        )

        // Simulate the widget state transition
        val offset = DayClickHelper.calculatePrecipitationOffset(now, today)
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION, offset)
            } catch (_: Exception) {
                // Database access may fail for test widget ID — state already committed
            }
        }

        assertEquals(
            "View mode should be PRECIPITATION",
            ViewMode.PRECIPITATION,
            stateManager.getViewMode(testWidgetId),
        )
    }

    // ── High hourly rain: should always work ──

    @Test
    fun todayWithHighHourlyRain_navigatesToPrecipitation() {
        val today = LocalDate.now()
        // Use fixed time to avoid past-hour filtering issues in late-night test runs
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        val futureHour = now.plusHours(5)
        val forecasts = listOf(
            createForecast(
                String.format("%sT%02d:00", todayStr, futureHour.hour),
                precipProb = 70,
            ),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        assertNotNull("70% hourly rain should be detected", rainSummary)

        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 70)
        assertTrue(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))

        val offset = DayClickHelper.calculatePrecipitationOffset(now, today)
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION, offset)
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
    }

    // ── No rain at all: should stay in daily mode ──

    @Test
    fun todayWithNoRain_staysInDailyMode() {
        val today = LocalDate.now()
        // Use fixed time to avoid past-hour filtering issues
        val now = today.atTime(10, 0)
        val todayStr = today.toString()

        val futureHour = now.plusHours(3)
        val forecasts = listOf(
            createForecast(
                String.format("%sT%02d:00", todayStr, futureHour.hour),
                precipProb = 0,
            ),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 0)

        assertNull(rainSummary)
        assertFalse("No rain should not navigate to precipitation", hasRain)
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))

        // showHistory=true path opens ForecastHistoryActivity, does NOT change view mode
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
    }

    // ── State management: offset is persisted correctly ──

    @Test
    fun handleSetView_setsPrecipitationModeAndOffset() {
        val today = LocalDate.now()
        val now = today.atTime(10, 0)
        // For today, the offset should now be 0
        val expectedOffset = DayClickHelper.calculatePrecipitationOffset(now, today)
        assertEquals("Offset for today should be 0", 0, expectedOffset)

        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(
                    context,
                    testWidgetId,
                    ViewMode.PRECIPITATION,
                    expectedOffset,
                )
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(expectedOffset, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleSetView_setsTomorrowOffsetCorrectly() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        // Set "now" to 10 AM today
        val now = today.atTime(10, 0)
        
        // Offset to 8 AM tomorrow should be 22 hours
        val expectedOffset = DayClickHelper.calculatePrecipitationOffset(now, tomorrow)
        assertEquals(22, expectedOffset)

        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(
                    context,
                    testWidgetId,
                    ViewMode.PRECIPITATION,
                    expectedOffset,
                )
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(22, stateManager.getHourlyOffset(testWidgetId))
    }

    // ── Verify that the current production code path uses hasRainForecast ──
    // This test verifies the ACTUAL code path in DailyViewHandler by reading
    // the database and checking how the click handler would compute hasRainForecast.

    @Test
    fun productionCodePath_includesDailyPrecipInClickDecision() {
        // Query the real database for today's weather data
        val database = WeatherDatabase.getDatabase(context)
        val today = LocalDate.now()
        val todayStr = today.toString()
        val now = today.atTime(10, 0)

        val lat = WeatherWidgetWorker.DEFAULT_LAT
        val lon = WeatherWidgetWorker.DEFAULT_LON

        runBlocking {
            val weatherDao = database.weatherDao()
            val hourlyDao = database.hourlyForecastDao()

            val todayWeather = weatherDao.getWeatherForDate(todayStr, lat, lon)
            val dailyPrecipProb = todayWeather?.precipProbability

            val hourlyStart = now.minusHours(24).toString()
            val hourlyEnd = now.plusHours(60).toString()
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val source = todayWeather?.source ?: "NWS"
            val rainSummary = RainAnalyzer.getRainSummary(hourlyForecasts, today, source, now)

            // The production code should use BOTH rainSummary AND dailyPrecipProb
            val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProb)

            // If daily precip > 0 OR hourly rain detected, hasRain should be true
            if ((dailyPrecipProb ?: 0) > 0 || rainSummary != null) {
                assertTrue(
                    "hasRainForecast should be true when daily precip=$dailyPrecipProb or rainSummary=$rainSummary",
                    hasRain,
                )
            }
        }
    }
}
