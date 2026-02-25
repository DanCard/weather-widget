package com.weatherwidget.widget.handlers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyViewHandlerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(RainAnalyzer)
    }

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
    }

    @Test
    fun `prepareTextDays numColumns=2 shows only 2 slots`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 2,
            displaySource = WeatherSource.NWS
        )

        assertEquals(7, result.size)
        assertEquals(2, result.count { it.isVisible })
        assertTrue(result[1].isVisible) // today
        assertTrue(result[2].isVisible) // tomorrow
    }

    @Test
    fun `prepareTextDays skipHistory shifts visible dates`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipHistory = true
        )

        val visibleDates = result.filter { it.isVisible }.map { it.dateStr }
        assertEquals(
            listOf(
                today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
            ),
            visibleDates
        )
    }

    @Test
    fun `prepareTextDays identifies first rainy day for text display`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } answers {
            val date = secondArg<LocalDate>()
            if (date == today.plusDays(1)) "9am"
            else if (date == today.plusDays(2)) "10am"
            else null
        }

        val weatherByDate = createWeatherMap(today)
        val result = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 5,
            displaySource = WeatherSource.NWS
        )

        val tomorrow = result.first { it.date == today.plusDays(1) }
        val dayAfter = result.first { it.date == today.plusDays(2) }

        assertTrue(tomorrow.showRain)
        assertEquals("9am", tomorrow.rainSummary)
        assertFalse(dayAfter.showRain) // Only first rainy day shows text
        assertEquals("10am", dayAfter.rainSummary) // Summary still exists for click logic
    }

    @Test
    fun `prepareGraphDays compositions triple line data for today`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0) // 8 PM
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Official API says 80/60
        val weatherByDate = mapOf(
            todayStr to createWeather(todayStr, highTemp = 80, lowTemp = 60)
        )
        
        // Hourly samples only reached 74/65
        val hourlyForecasts = (0..23).map { hour ->
            HourlyForecastEntity(
                dateTime = today.atTime(hour, 0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")),
                condition = "Clear",
                source = WeatherSource.NWS.id,
                temperature = (65 + (hour % 10)).toFloat(), // Max 74
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            )
        }

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            accuracyMode = AccuracyDisplayMode.FORECAST_BAR,
            displaySource = WeatherSource.NWS,
            isEveningMode = true,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts
        )

        val todayData = days.first { it.date == todayStr }
        // Refined logic: Observed uses hourly peak (74) if after 4 PM, 
        // but Forecast line (Blue) uses official API (80)
        assertEquals(74f, todayData.high!!, 0.1f) 
        assertEquals(65f, todayData.low!!, 0.1f)
        assertEquals(80f, todayData.forecastHigh!!, 0.1f)
        assertEquals(60f, todayData.forecastLow!!, 0.1f)
    }

    @Test
    fun `buildDayClickIntent returns correct extras with Robolectric`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val dateStr = "2030-06-16" // Tomorrow
        
        val intent = DailyViewHandler.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            dateStr = dateStr,
            hasRainForecast = true,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertEquals(dateStr, intent.getStringExtra("date"))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra("com.weatherwidget.EXTRA_TARGET_VIEW"))
        assertEquals(20, intent.getIntExtra("com.weatherwidget.EXTRA_HOURLY_OFFSET", -1))
    }

    private fun createWeatherMap(today: LocalDate): Map<String, WeatherEntity> {
        return (-1..5).associate { offset ->
            val date = today.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            dateStr to createWeather(dateStr)
        }
    }

    private fun createWeather(
        date: String,
        precipProbability: Int? = 0,
        highTemp: Int? = 70,
        lowTemp: Int? = 55,
    ): WeatherEntity {
        return WeatherEntity(
            date = date,
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            currentTemp = 62,
            condition = "Clear",
            isActual = false,
            source = WeatherSource.NWS.id,
            precipProbability = precipProbability,
            fetchedAt = 1L,
        )
    }
}
