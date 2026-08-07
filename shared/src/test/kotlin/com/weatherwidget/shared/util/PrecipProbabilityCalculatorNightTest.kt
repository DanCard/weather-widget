package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * Tests for [PrecipProbabilityCalculator.isNext8HourPrecipPredominantlyNight], the shared
 * port of what used to live in Android's HeaderPrecipCalculator. The header rain-chance font
 * shrink (NIGHT_SCALE) on both platforms depends on this verdict.
 */
@Category(ShortDuration::class)
class PrecipProbabilityCalculatorNightTest {

    private val zone = ZoneId.systemDefault()

    @Test
    fun `returns false when peak rain is during the day`() {
        // referenceTime = 08:00; all rain is 10:00–14:00 (daytime, sunrise=6, sunset=20)
        val forecasts = listOf(
            hourly("2026-02-22T10:00", "NWS", 80),
            hourly("2026-02-22T12:00", "NWS", 60),
            hourly("2026-02-22T14:00", "NWS", 40),
        )
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 8, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(false, result)
    }

    @Test
    fun `returns true when peak rain is at night`() {
        // referenceTime = 20:00; all rain is 21:00–23:00 (nighttime, sunset=20)
        val forecasts = listOf(
            hourly("2026-02-22T21:00", "NWS", 70),
            hourly("2026-02-22T22:00", "NWS", 80),
            hourly("2026-02-22T23:00", "NWS", 50),
        )
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 20, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `returns true when night probability mass exceeds day`() {
        // referenceTime = 17:00, sunset = 19:00: 2h daytime at 40%, 6h nighttime at 60%
        val forecasts = listOf(
            hourly("2026-02-22T17:00", "NWS", 40),
            hourly("2026-02-22T19:00", "NWS", 60),
            hourly("2026-02-22T21:00", "NWS", 60),
        )
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 17, 0),
            sunriseHour = 6.0,
            sunsetHour = 19.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `null source counts as the display source`() {
        // Shared convention (same as getNext8HourPrecipProbability): a null-source row matches
        // the display source. Night rain on a null-source row must therefore shrink the header.
        val forecasts = listOf(
            hourly("2026-02-22T22:00", null, 90),
            hourly("2026-02-22T23:00", null, 90),
        )
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 21, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `falls back to fallback source when display source has no data`() {
        val forecasts = listOf(
            hourly("2026-02-22T22:00", WeatherSource.GENERIC_GAP.id, 90),
            hourly("2026-02-22T23:00", WeatherSource.GENERIC_GAP.id, 90),
        )
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 21, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `returns false when forecasts are empty`() {
        val result = night(
            emptyList(),
            referenceTime = LocalDateTime.of(2026, 2, 22, 22, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(false, result)
    }

    @Test
    fun `returns false for midnight sun`() {
        val forecasts = listOf(hourly("2026-02-22T22:00", "NWS", 90))
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 21, 0),
            sunriseHour = 0.0,
            sunsetHour = 24.0, // sun never sets
        )
        assertEquals(false, result)
    }

    @Test
    fun `returns true for polar night`() {
        val forecasts = listOf(hourly("2026-02-22T14:00", "NWS", 50))
        val result = night(
            forecasts,
            referenceTime = LocalDateTime.of(2026, 2, 22, 13, 0),
            sunriseHour = 0.0, // sun never rises
            sunsetHour = 18.0,
        )
        assertEquals(true, result)
    }

    private fun night(
        forecasts: List<HourlyForecast>,
        referenceTime: LocalDateTime,
        sunriseHour: Double,
        sunsetHour: Double,
    ): Boolean = PrecipProbabilityCalculator.isNext8HourPrecipPredominantlyNight(
        hourlyForecasts = forecasts,
        displaySourceId = WeatherSource.NWS.id,
        fallbackSourceId = WeatherSource.GENERIC_GAP.id,
        referenceTime = referenceTime,
        sunriseHour = sunriseHour,
        sunsetHour = sunsetHour,
    )

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
}
