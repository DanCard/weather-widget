package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class DailyViewHandlerUnitTest {

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyExtreme(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = WeatherSource.NWS.id,
        locationLat = 37.422,
        locationLon = -122.0841,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun navigationUtils_getDayOffsets_returnsCorrectNumber() {
        val numColumns = 3
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

        assertEquals(numColumns, dayOffsets.size)
    }

    @Test
    fun navigationUtils_getDayOffsets_includesToday() {
        val numColumns = 3
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

        assertTrue("Today (offset 0) should be included", dayOffsets.contains(0))
    }

    @Test
    fun buildTodayHighProvenanceMessage_includesForecastActualGraphAndStationMaxes() {
        val today = LocalDate.of(2026, 5, 18)
        val timestamp = LocalDateTime.of(2026, 5, 18, 16, 55)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val message = DailyViewHandler.buildTodayHighProvenanceMessage(
            appWidgetId = 74,
            today = today,
            displaySource = WeatherSource.NWS,
            forecastWeather = ForecastEntity(
                targetDate = today.toEpochDay() * 86_400_000L,
                forecastDate = today.toEpochDay() * 86_400_000L,
                locationLat = 37.422,
                locationLon = -122.0841,
                highTemp = 83f,
                lowTemp = 58f,
                condition = "Sunny",
                source = WeatherSource.NWS.id,
            ),
            dailyActual = extreme(today, 83.40072f, 62.006f),
            todayDay = DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Today",
                solidLineHigh = 81.7f,
                solidLineLow = 63.9f,
                dashedLineHigh = 83f,
                dashedLineLow = 58f,
                iconRes = 0,
                isToday = true,
                ghostLineHigh = 83.40072f,
                snapshotHigh = 81f,
            ),
            currentTemp = 81.7f,
            observedAt = timestamp,
            observations = listOf(
                ObservationEntity(
                    stationId = "AW020",
                    stationName = "AE6EO MOUNTAIN VIEW",
                    timestamp = timestamp,
                    temperature = 84.002f,
                    condition = "Clear",
                    locationLat = 37.422,
                    locationLon = -122.0841,
                    distanceKm = 2.94f,
                    api = WeatherSource.NWS.id,
                ),
                ObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Mountain View, Moffett Field",
                    timestamp = timestamp,
                    temperature = 80.6f,
                    condition = "Clear",
                    locationLat = 37.422,
                    locationLon = -122.0841,
                    distanceKm = 3.66f,
                    api = WeatherSource.NWS.id,
                ),
            ),
        )

        assertTrue(message.contains("widget=74 date=2026-05-18 source=NWS"))
        assertTrue(message.contains("forecastHigh=83.00"))
        assertTrue(message.contains("dailyActualHigh=83.40"))
        assertTrue(message.contains("graphGhostHigh=83.40"))
        assertTrue(message.contains("stationMaxes=[AW020(max=84.00@16:55:00"))
        assertTrue(message.contains("KNUQ(max=80.60@16:55:00"))
    }
}
