package com.weatherwidget.desktop

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopPrecipSourceIsolationTest {
    private fun row(stationId: String, api: WeatherSource) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = 1L,
        temperature = 60f,
        condition = "Rain",
        locationLat = 37.42,
        locationLon = -122.08,
        api = api.id,
        precipAmountMm = 1f,
    )

    @Test
    fun `Open Meteo model rows and other APIs cannot drive actual precipitation`() {
        val rows = listOf(
            row("OPEN_METEO_MAIN", WeatherSource.OPEN_METEO),
            row("KNUQ", WeatherSource.NWS),
        )

        assertTrue(actualPrecipRowsForSource(rows, WeatherSource.OPEN_METEO.id).isEmpty())
    }

    @Test
    fun `approved provider history keeps only its own synthetic row`() {
        val rows = listOf(
            row("WEATHER_API_MAIN", WeatherSource.WEATHER_API),
            row("WEATHER_API_ALT", WeatherSource.WEATHER_API),
            row("KNUQ", WeatherSource.NWS),
        )

        assertEquals(
            listOf("WEATHER_API_MAIN"),
            actualPrecipRowsForSource(rows, WeatherSource.WEATHER_API.id).map { it.stationId },
        )
    }
}
