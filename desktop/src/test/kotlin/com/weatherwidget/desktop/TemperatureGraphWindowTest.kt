package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class TemperatureGraphWindowTest {

    @Test
    fun `test temperatureGraphHourWindow aligns correctly`() {
        val zone = ZoneId.of("America/New_York")
        
        // Test case 1: 12:33 -> aligned center should round UP to 13:00.
        // NARROW (back=2, forward=2): start 11:00, end 15:00
        val time1 = LocalDateTime.of(2026, 6, 7, 12, 33, 0).atZone(zone).toInstant().toEpochMilli()
        val window1 = temperatureGraphHourWindow(time1, backHours = 2, forwardHours = 2, zoneId = zone)
        
        val expectedStart1 = LocalDateTime.of(2026, 6, 7, 11, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val expectedEnd1 = LocalDateTime.of(2026, 6, 7, 15, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart1, window1.startMs)
        assertEquals(expectedEnd1, window1.endMs)

        // Test case 2: 12:20 -> aligned center should remain 12:00.
        // NARROW (back=2, forward=2): start 10:00, end 14:00
        val time2 = LocalDateTime.of(2026, 6, 7, 12, 20, 0).atZone(zone).toInstant().toEpochMilli()
        val window2 = temperatureGraphHourWindow(time2, backHours = 2, forwardHours = 2, zoneId = zone)
        
        val expectedStart2 = LocalDateTime.of(2026, 6, 7, 10, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val expectedEnd2 = LocalDateTime.of(2026, 6, 7, 14, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart2, window2.startMs)
        assertEquals(expectedEnd2, window2.endMs)
    }

    @Test
    fun `test actual observation line matches graph left and right boundaries`() {
        val zone = ZoneId.of("America/New_York")
        val nowLocal = LocalDateTime.of(2026, 6, 7, 12, 33, 0)
        val nowMs = nowLocal.atZone(zone).toInstant().toEpochMilli()

        // Generate hourly forecasts at every top-of-hour across -6h to +6h
        val baseTime = nowLocal.truncatedTo(ChronoUnit.HOURS)
        val hourly = (-6..6).map { hOffset ->
            val dt = baseTime.plusHours(hOffset.toLong()).atZone(zone).toInstant().toEpochMilli()
            HourlyForecast(
                dateTime = dt,
                temperature = 70f + hOffset,
                condition = "Clear",
                source = WeatherSource.NWS.id
            )
        }

        // Generate observations every 20 minutes in the past
        // Include observations starting before the graph start boundary (-6h) to ensure carry path is checked.
        val observations = (-30..0).map { mOffset ->
            val ts = nowMs + mOffset * 20 * 60000L
            ObservationReading(
                stationId = "KNYC",
                stationName = "Central Park",
                timestamp = ts,
                temperature = 68f,
                condition = "observed",
                locationLat = 40.7829,
                locationLon = -73.9654,
                distanceKm = 1.0f,
                api = WeatherSource.NWS.id
            )
        }

        val backHours = 2
        val forwardHours = 2
        
        // 1. Compute window using desktop's helper
        val window = temperatureGraphHourWindow(nowMs, backHours, forwardHours, zone)
        val points = hourly.filter { it.dateTime >= window.startMs && it.dateTime < window.endMs }.sortedBy { it.dateTime }

        // 2. Call the shared ActualTemperatureSeriesBuilder
        val series = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = hourly,
            observations = observations,
            centerTime = nowLocal,
            displaySourceId = WeatherSource.NWS.id,
            userLat = 40.7829,
            userLon = -73.9654,
            backHours = backHours.toLong(),
            forwardHours = forwardHours.toLong(),
            contextLookbackHours = 72L,
            contextLookaheadHours = 60L,
            now = nowLocal,
            zoneId = zone,
            smoothedForecasts = null
        )

        // Find the actual line points (which represent historical actual observations)
        val actualPoints = series.points.filter { it.isActual && it.actualTemp != null }

        // Assert that the first actual temperature point starts exactly at the left edge of the desktop window
        assertEquals(window.startMs, actualPoints.first().timeMs)
        assertEquals(points.first().dateTime, actualPoints.first().timeMs)

        // Assert that the last point in the filtered hourly forecast matches the right edge of the window
        assertEquals(window.endMs - 3_600_000L, points.last().dateTime)
    }
}
