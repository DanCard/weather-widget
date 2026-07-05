package com.weatherwidget.data.repository

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import io.mockk.mockk
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class ForecastRepositoryDayNightPrecipTest {

    @Test
    fun `mapDailyForecast calculates daytime and nighttime max probability from hourly data`() {
        val repository = ForecastRepository(
            context = mockk(),
            forecastDao = mockk(),
            hourlyForecastDao = mockk(),
            hourlyForecastHistoryDao = mockk(),
            appLogDao = mockk(),
            nwsApi = mockk(),
            openMeteoApi = mockk(),
            visualCrossingApi = mockk(),
            weatherApi = mockk(),
            silurianApi = mockk(),
            widgetStateManager = mockk(),
            climateNormalDao = mockk(),
            observationDao = mockk(),
            dailyHistoryDao = mockk(),
            observationRepository = mockk(),
            nwsForecastMapper = mockk()
        )

        val targetDate = LocalDate.of(2026, 5, 25)
        val zone = ZoneId.systemDefault()
        
        // Daytime: 8 AM - 8 PM
        val t08 = targetDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val t12 = targetDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val t19 = targetDate.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        
        // Nighttime: 8 PM - 8 AM next day
        val t20 = targetDate.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val t23 = targetDate.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val t04next = targetDate.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val t07next = targetDate.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            HourlyForecast(dateTime = t08, temperature = 60f, condition = "Cloudy", precipProbability = 10),
            HourlyForecast(dateTime = t12, temperature = 70f, condition = "Cloudy", precipProbability = 50),
            HourlyForecast(dateTime = t19, temperature = 65f, condition = "Cloudy", precipProbability = 20),
            
            HourlyForecast(dateTime = t20, temperature = 60f, condition = "Rain", precipProbability = 80),
            HourlyForecast(dateTime = t23, temperature = 55f, condition = "Rain", precipProbability = 100),
            HourlyForecast(dateTime = t04next, temperature = 50f, condition = "Rain", precipProbability = 30),
            HourlyForecast(dateTime = t07next, temperature = 52f, condition = "Rain", precipProbability = 10)
        )

        val daily = DailyForecast(
            date = "2026-05-25",
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Rain",
            precipProbability = 100
        )

        val result = repository.mapDailyForecast(
            day = daily,
            latitude = 0.0,
            longitude = 0.0,
            sourceId = WeatherSource.OPEN_METEO.id,
            hourlyForecasts = hourly
        )

        assertEquals(50, result.daytimePrecipProbability) // Max of 10, 50, 20
        assertEquals(100, result.nighttimePrecipProbability) // Max of 80, 100, 30, 10
    }

    @Test
    fun `mapDailyForecast returns null when no hourly data matches windows`() {
        val repository = ForecastRepository(
            context = mockk(),
            forecastDao = mockk(),
            hourlyForecastDao = mockk(),
            hourlyForecastHistoryDao = mockk(),
            appLogDao = mockk(),
            nwsApi = mockk(),
            openMeteoApi = mockk(),
            visualCrossingApi = mockk(),
            weatherApi = mockk(),
            silurianApi = mockk(),
            widgetStateManager = mockk(),
            climateNormalDao = mockk(),
            observationDao = mockk(),
            dailyHistoryDao = mockk(),
            observationRepository = mockk(),
            nwsForecastMapper = mockk()
        )

        val daily = DailyForecast(
            date = "2026-05-25",
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Rain",
            precipProbability = 100
        )

        val result = repository.mapDailyForecast(
            day = daily,
            latitude = 0.0,
            longitude = 0.0,
            sourceId = WeatherSource.OPEN_METEO.id,
            hourlyForecasts = emptyList()
        )

        assertEquals(null, result.daytimePrecipProbability)
        assertEquals(null, result.nighttimePrecipProbability)
    }
}
