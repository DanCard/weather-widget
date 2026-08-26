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
    fun `Silurian model rows and other APIs cannot drive actual precipitation`() {
        val rows = listOf(
            row("SILURIAN_MAIN", WeatherSource.SILURIAN),
            row("KNUQ", WeatherSource.NWS),
        )

        assertTrue(actualPrecipRowsForSource(rows, WeatherSource.SILURIAN.id).isEmpty())
    }

    @Test
    fun `approved provider history keeps only its own synthetic row`() {
        val rows = listOf(
            row("OPEN_METEO_MAIN", WeatherSource.OPEN_METEO),
            row("OPEN_METEO_ALT", WeatherSource.OPEN_METEO),
            row("KNUQ", WeatherSource.NWS),
        )

        assertEquals(
            listOf("OPEN_METEO_MAIN"),
            actualPrecipRowsForSource(rows, WeatherSource.OPEN_METEO.id).map { it.stationId },
        )
    }
}
