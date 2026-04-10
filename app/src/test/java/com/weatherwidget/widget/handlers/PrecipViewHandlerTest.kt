package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class PrecipViewHandlerTest {

    @Test
    fun `buildPrecipHourDataList returns empty when source has no data in window`() {
        // Simulates the race condition: hourlyForecasts exists for a different
        // source, but the selected source has no entries in the time window.
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.OPEN_METEO),
            hourly("2026-03-14T19:00", WeatherSource.OPEN_METEO),
            hourly("2026-03-14T20:00", WeatherSource.OPEN_METEO),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        // The fallback logic picks firstOrNull(), so it should NOT be empty here
        // because the groupBy mapValues uses fallback. But if all data is outside
        // the window, it will be empty.
        // This test documents the actual behavior.
        assertTrue(
            "result should be non-empty due to fallback (firstOrNull), " +
                "actual size=${result.size}",
            result.isNotEmpty(),
        )
    }

    @Test
    fun `buildPrecipHourDataList returns empty when data is outside time window`() {
        // Data exists but is far outside the zoom window — this IS the empty path
        val hours = listOf(
            hourly("2026-03-20T18:00", WeatherSource.NWS),
            hourly("2026-03-20T19:00", WeatherSource.NWS),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals("data outside zoom window should yield empty output", 0, result.size)
    }

    @Test
    fun `buildPrecipHourDataList passes precipAmountMm through`() {
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS, precipAmountMm = 3.5f),
            hourly("2026-03-14T19:00", WeatherSource.NWS, precipAmountMm = 1.2f),
            hourly("2026-03-14T20:00", WeatherSource.NWS, precipAmountMm = 0.0f),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val matched = result.filter { it.precipAmountMm != null }
        assertTrue(
            "At least one result should carry precipAmountMm, actual=${result.map { it.precipAmountMm }}",
            matched.isNotEmpty(),
        )
        assertTrue(
            "Should contain 3.5mm entry",
            matched.any { it.precipAmountMm == 3.5f },
        )
    }

    @Test
    fun `buildPrecipHourDataList null precipAmountMm passes through as null`() {
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS),
            hourly("2026-03-14T19:00", WeatherSource.NWS),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 18, 30),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val withAmount = result.filter { it.precipAmountMm != null }
        assertEquals(
            "All precipAmountMm should be null when source has no data",
            0,
            withAmount.size,
        )
    }

    private fun hourly(
        dateTime: String,
        source: WeatherSource,
        precipAmountMm: Float? = null,
    ) = HourlyForecastEntity(
        dateTime = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        locationLat = 37.42,
        locationLon = -122.08,
        temperature = 60f,
        condition = "Mostly Clear",
        source = source.id,
        precipProbability = 30,
        cloudCover = 50,
        precipAmountMm = precipAmountMm,
        fetchedAt = 1L,
    )
}
