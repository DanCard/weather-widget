package com.weatherwidget.widget.handlers

import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DailyViewHandlerTest {

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
    }

    @Test
    fun `updateTextMode numColumns=2 shows only day2 and day3 slots`() {
        mockkObject(RainAnalyzer)
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val today = LocalDate.of(2030, 6, 15)
        val views = mockk<RemoteViews>(relaxed = true)
        val weatherByDate =
            listOf(
                today.minusDays(1),
                today,
                today.plusDays(1),
            ).associate { date ->
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                dateStr to createWeather(dateStr)
            }

        invokeUpdateTextMode(
            views = views,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 2,
            stateManager = null,
        )

        verify { views.setViewVisibility(R.id.day1_container, View.GONE) }
        verify { views.setViewVisibility(R.id.day2_container, View.VISIBLE) }
        verify { views.setViewVisibility(R.id.day3_container, View.VISIBLE) }
        verify { views.setViewVisibility(R.id.day4_container, View.GONE) }
        verify { views.setViewVisibility(R.id.day5_container, View.GONE) }
        verify { views.setViewVisibility(R.id.day6_container, View.GONE) }
        verify { views.setViewVisibility(R.id.day7_container, View.GONE) }
    }

    @Test
    fun `updateTextMode skipHistory shifts visible dates to today and future`() {
        mockkObject(RainAnalyzer)
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val today = LocalDate.of(2030, 6, 15)
        val views = mockk<RemoteViews>(relaxed = true)
        val weatherByDate =
            listOf(
                today,
                today.plusDays(1),
                today.plusDays(2),
            ).associate { date ->
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                dateStr to createWeather(dateStr)
            }

        val result =
            invokeUpdateTextMode(
                views = views,
                centerDate = today,
                today = today,
                weatherByDate = weatherByDate,
                hourlyForecasts = emptyList(),
                numColumns = 3,
                skipHistory = true,
                stateManager = null,
            )

        assertEquals(
            listOf(
                today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE),
            ),
            result.map { it.second },
        )
    }

    @Test
    fun `updateTextMode keeps hasRainForecast true for clicks even when today's rain text is suppressed`() {
        mockkObject(RainAnalyzer)

        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val views = mockk<RemoteViews>(relaxed = true)
        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.wasRainShownToday(any(), todayStr) } returns true

        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } answers {
            val targetDate = secondArg<LocalDate>()
            if (targetDate == today) "2pm" else null
        }

        val weatherByDate =
            listOf(
                today.minusDays(1),
                today,
                today.plusDays(1),
            ).associate { date ->
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                dateStr to createWeather(dateStr, precipProbability = null)
            }

        val result =
            invokeUpdateTextMode(
                views = views,
                centerDate = today,
                today = today,
                weatherByDate = weatherByDate,
                hourlyForecasts = emptyList(),
                numColumns = 3,
                stateManager = stateManager,
            )

        val todayEntry = result.first { it.second == todayStr }
        assertTrue(todayEntry.third)
        verify { views.setViewVisibility(R.id.day2_rain, View.GONE) }
    }

    private fun createWeather(date: String, precipProbability: Int? = 0): WeatherEntity {
        return WeatherEntity(
            date = date,
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = 70,
            lowTemp = 55,
            currentTemp = 62,
            condition = "Clear",
            isActual = false,
            source = WeatherSource.NWS.id,
            precipProbability = precipProbability,
            fetchedAt = 1L,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeUpdateTextMode(
        views: RemoteViews,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource = WeatherSource.NWS,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 42,
        todayNext8HourPrecipProbability: Int? = null,
    ): List<Triple<Int, String, Boolean>> {
        val method =
            DailyViewHandler::class.java.getDeclaredMethod(
                "updateTextMode",
                RemoteViews::class.java,
                LocalDate::class.java,
                LocalDate::class.java,
                Map::class.java,
                List::class.java,
                Int::class.javaPrimitiveType,
                WeatherSource::class.java,
                Boolean::class.javaPrimitiveType,
                WidgetStateManager::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaObjectType,
            )
        method.isAccessible = true
        return method.invoke(
            DailyViewHandler,
            views,
            centerDate,
            today,
            weatherByDate,
            hourlyForecasts,
            numColumns,
            displaySource,
            skipHistory,
            stateManager,
            appWidgetId,
            todayNext8HourPrecipProbability,
        ) as List<Triple<Int, String, Boolean>>
    }
}
