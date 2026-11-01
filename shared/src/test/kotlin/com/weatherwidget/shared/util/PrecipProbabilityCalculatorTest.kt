package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class PrecipProbabilityCalculatorTest {

    private val zone = ZoneId.systemDefault()

    private fun hourly(dateTime: String, source: String?, precipProbability: Int?): HourlyForecast {
        val ms = LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()
        return HourlyForecast(
            dateTime = ms,
            temperature = 60f,
            condition = "Rain",
            precipProbability = precipProbability,
            source = source,
        )
    }

    @Test
    fun `maxPrecipProbabilityWithin honors lookaheadHours parameter`() {
        val ref = LocalDateTime.of(2026, 5, 1, 6, 0)
        // Rain spike at +7 hours (13:00)
        val forecasts = listOf(
            hourly("2026-05-01T06:00", "NWS", 0),
            hourly("2026-05-01T10:00", "NWS", 0),
            hourly("2026-05-01T13:00", "NWS", 80),
        )

        // With 6-hour lookahead (until 12:00), the spike at 13:00 is outside
        val max6h = PrecipProbabilityCalculator.maxPrecipProbabilityWithin(
            lookaheadHours = 6L,
            hourlyForecasts = forecasts,
            displaySourceId = "NWS",
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = null,
            referenceTime = ref,
        )
        assertEquals(0, max6h)

        // With 8-hour lookahead (until 14:00), the spike at 13:00 is captured
        val max8h = PrecipProbabilityCalculator.maxPrecipProbabilityWithin(
            lookaheadHours = 8L,
            hourlyForecasts = forecasts,
            displaySourceId = "NWS",
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = null,
            referenceTime = ref,
        )
        assertEquals(80, max8h)
    }

    @Test
    fun `six hour window excludes its exact endpoint`() {
        val ref = LocalDateTime.of(2026, 2, 22, 14, 0)
        fun at(hour: Int, precip: Int) = HourlyForecast(
            dateTime = ref.withHour(hour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            temperature = 60f,
            condition = "Rain",
            precipProbability = precip,
            source = WeatherSource.NWS.id,
            fetchedAt = 0L,
            locationLat = 37.422,
            locationLon = -122.084,
        )
        val result = PrecipProbabilityCalculator.getNext6HourPrecipProbability(
            listOf(at(14, 30), at(15, 30), at(20, 100)),
            WeatherSource.NWS.id,
            WeatherSource.GENERIC_GAP.id,
            null,
            ref,
        )
        assertEquals(30, result)
    }

    @Test
    fun `getNext6HourPrecipProbability delegates to 6-hour window`() {
        val ref = LocalDateTime.of(2026, 5, 1, 6, 0)
        // Rain spike at +7 hours (13:00)
        val forecasts = listOf(
            hourly("2026-05-01T06:00", "NWS", 10),
            hourly("2026-05-01T11:00", "NWS", 20),
            hourly("2026-05-01T13:00", "NWS", 90),
        )

        val result6h = PrecipProbabilityCalculator.getNext6HourPrecipProbability(
            hourlyForecasts = forecasts,
            displaySourceId = "NWS",
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = 5,
            referenceTime = ref,
        )

        val direct6hResult = PrecipProbabilityCalculator.maxPrecipProbabilityWithin(
            lookaheadHours = 6L,
            hourlyForecasts = forecasts,
            displaySourceId = "NWS",
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = 5,
            referenceTime = ref,
        )

        assertEquals(direct6hResult, result6h)
        // 13:00 is past 6h lookahead (12:00), so 90% is excluded
        assertEquals(20, result6h)
    }

}
