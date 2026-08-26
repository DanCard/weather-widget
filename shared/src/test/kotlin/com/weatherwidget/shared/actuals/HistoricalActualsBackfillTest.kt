package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HistoricalActualsBackfillTest {

    private val lat = 37.4220
    private val lon = -122.0841
    private val now = 1_700_000_000_000L
    private val hourMs = 3_600_000L

    private fun hour(offsetHours: Long, temp: Float, precipMm: Float? = null) =
        HourlyForecast(
            dateTime = now + offsetHours * hourMs,
            temperature = temp,
            condition = "Clear",
            precipAmountMm = precipMm,
        )

    @Test
    fun `keeps only past hours up to now, excludes the future`() {
        val hourly = listOf(
            hour(-2, 60f),
            hour(-1, 62f),
            hour(0, 64f),   // exactly now -> kept (dateTime <= now)
            hour(1, 66f),   // future -> excluded
            hour(2, 68f),
        )

        val result = HistoricalActualsBackfill.build(
            hourly, lat, lon, WeatherSource.TOMORROW_IO.id, now,
        )

        assertEquals(listOf(60f, 62f, 64f), result.map { it.temperature })
        assertTrue(result.all { it.timestamp <= now })
    }

    @Test
    fun `maps source id, station id, condition and location`() {
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f)), lat, lon, WeatherSource.WEATHER_API.id, now,
        )

        val obs = result.single()
        assertEquals(WeatherSource.WEATHER_API.id, obs.api)
        assertEquals("WEATHER_API_MAIN", obs.stationId)
        assertEquals(HistoricalActualsBackfill.syntheticStationId(WeatherSource.WEATHER_API.id), obs.stationId)
        assertEquals("Clear", obs.condition)
        assertEquals(lat, obs.locationLat, 0.0)
        assertEquals(lon, obs.locationLon, 0.0)
        assertEquals(0f, obs.distanceKm)
    }

    @Test
    fun `keeps measured precip for sources that provide historical actuals`() {
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f, precipMm = 1.5f)), lat, lon, WeatherSource.TOMORROW_IO.id, now,
        )
        assertEquals(1.5f, result.single().precipAmountMm)
    }

    @Test
    fun `nulls measured precip for forecast-only sources`() {
        // Visual Crossing's past hours are its own forecast, not a measurement.
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f, precipMm = 1.5f)), lat, lon, WeatherSource.VISUAL_CROSSING.id, now,
        )
        assertNull(result.single().precipAmountMm)
    }

    @Test
    fun `silurian forecast history produces no synthetic actual rows`() {
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f, precipMm = 1.5f)), lat, lon, WeatherSource.SILURIAN.id, now,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `open meteo history produces synthetic actual rows`() {
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f, precipMm = 1.5f)), lat, lon, WeatherSource.OPEN_METEO.id, now,
        )

        assertEquals(1, result.size)
        assertEquals("OPEN_METEO_MAIN", result.single().stationId)
        assertEquals(62f, result.single().temperature)
    }

    @Test
    fun `tomorrow timeline history uses deletable recent-history provenance`() {
        val result = HistoricalActualsBackfill.build(
            listOf(hour(-1, 62f, precipMm = 1.5f)), lat, lon, WeatherSource.TOMORROW_IO.id, now,
        )

        assertEquals(1, result.size)
        assertEquals(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, result.single().stationId)
        assertEquals(TomorrowIoActuals.RECENT_HISTORY_STATION_NAME, result.single().stationName)
        assertEquals(WeatherSource.TOMORROW_IO.id, result.single().api)
        assertEquals(1.5f, result.single().precipAmountMm)
    }

    @Test
    fun `syntheticStationId appends _MAIN to the source id`() {
        assertEquals("NWS_MAIN", HistoricalActualsBackfill.syntheticStationId(WeatherSource.NWS.id))
        assertEquals("SILURIAN_MAIN", HistoricalActualsBackfill.syntheticStationId(WeatherSource.SILURIAN.id))
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(
            HistoricalActualsBackfill.build(
                emptyList(), lat, lon, WeatherSource.OPEN_METEO.id, now,
            ).isEmpty()
        )
    }
}
