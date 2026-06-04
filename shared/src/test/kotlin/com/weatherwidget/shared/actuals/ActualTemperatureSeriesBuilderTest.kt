package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ActualTemperatureSeriesBuilderTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val center = LocalDateTime.parse("2026-06-03T12:00:00")

    @Test
    fun `build injects blended actuals and carries latest actual across past hours`() {
        val forecasts = forecasts("2026-06-03T08:00:00", 8)
        val observations = listOf(
            observation("S_NEAR", "2026-06-03T10:10:00", 70f, distanceKm = 2f),
            observation("S_FAR", "2026-06-03T10:10:00", 80f, distanceKm = 10f),
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = center,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-03T12:30:00"),
            zoneId = zone,
        )

        val actualPoint = result.points.single { it.timeMs == epoch("2026-06-03T10:10:00") }
        assertTrue(actualPoint.isActual)
        assertTrue(actualPoint.isObservedActual)
        assertEquals(70.38f, actualPoint.actualTemp!!, 0.05f)

        val carriedNoon = result.points.single { it.timeMs == epoch("2026-06-03T12:00:00") }
        assertTrue(carriedNoon.isActual)
        assertFalse(carriedNoon.isObservedActual)
        assertEquals(actualPoint.actualTemp!!, carriedNoon.actualTemp!!, 0.001f)
    }

    @Test
    fun `non NWS source selects the station with best coverage before building actuals`() {
        val forecasts = forecasts("2026-06-03T08:00:00", 8, source = WeatherSource.OPEN_METEO.id)
        val observations = listOf(
            observation("ONE", "2026-06-03T10:00:00", 60f, api = WeatherSource.OPEN_METEO.id, distanceKm = 1f),
            observation("TWO", "2026-06-03T10:00:00", 80f, api = WeatherSource.OPEN_METEO.id, distanceKm = 2f),
            observation("TWO", "2026-06-03T11:00:00", 82f, api = WeatherSource.OPEN_METEO.id, distanceKm = 2f),
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = center,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-03T12:30:00"),
            zoneId = zone,
        )

        assertEquals("TWO", result.selectedStationId)
        assertEquals(80f, result.points.single { it.timeMs == epoch("2026-06-03T10:00:00") }.actualTemp!!, 0.001f)
    }

    private fun forecasts(start: String, count: Int, source: String = WeatherSource.NWS.id): List<HourlyForecast> {
        val startTime = LocalDateTime.parse(start)
        return (0..count).map { index ->
            val time = startTime.plusHours(index.toLong())
            HourlyForecast(
                dateTime = time.atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f + index,
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        api: String = WeatherSource.NWS.id,
        distanceKm: Float,
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = epoch(time),
            temperature = temperature,
            condition = "observed",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = distanceKm,
            api = api,
        )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}
