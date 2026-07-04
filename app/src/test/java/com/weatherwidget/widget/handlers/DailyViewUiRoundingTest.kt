package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.WidgetConstants
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyViewUiRoundingTest {

    private val today = LocalDate.now()
    private val tomorrow = today.plusDays(1)
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrowStr = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyHistory(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = WeatherSource.OPEN_METEO.id,
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `UI shows the tenth for today history and future forecast alike`() {
        val now = LocalDateTime.now()
        val displaySource = WeatherSource.OPEN_METEO
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Manually inject decimals for yesterday, today, and tomorrow
        val weatherByDate = mapOf(
            yesterday to createForecast(yesterdayStr, 72.4f, 50.6f),
            today to createForecast(todayStr, 72.4f, 50.6f),
            tomorrow to createForecast(tomorrowStr, 72.4f, 50.6f)
        )

        val textDays = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 7,
            displaySource = displaySource,
            dailyActuals = mapOf(
                yesterday to extreme(yesterday, 72.4f, 50.6f),
                today to extreme(today, 72.4f, 50.6f),
            )
        )

        // 1. Verify History (Should show decimal)
        val historyData = textDays.find { it.dateStr == yesterdayStr }!!
        assertEquals("Yesterday high should show decimal in UI", "72.4°", historyData.highLabel)
        assertEquals("Yesterday low should show decimal in UI", "50.6°", historyData.lowLabel)

        // 2. Verify Today (Should show decimal)
        val todayData = textDays.find { it.dateStr == todayStr }!!
        assertEquals("Today high should show decimal in UI", "72.4°", todayData.highLabel)
        assertEquals("Today low should show decimal in UI", "50.6°", todayData.lowLabel)

        // 3. Verify Tomorrow forecast (now also shows the tenth)
        val tomorrowData = textDays.find { it.dateStr == tomorrowStr }!!
        assertEquals("Tomorrow high should show decimal in UI", "72.4°", tomorrowData.highLabel)
        assertEquals("Tomorrow low should show decimal in UI", "50.6°", tomorrowData.lowLabel)
    }

    private fun createForecast(date: String, high: Float, low: Float): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = 0.0,
            locationLon = 0.0,
            highTemp = high,
            lowTemp = low,
            condition = "Clear",
            source = WeatherSource.OPEN_METEO.id,
            fetchedAt = 1L
        )
    }
}
