package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CloudCoverViewHandlerTest {

    @Test
    fun `selectCloudCoverSource keeps requested source when it has visible cloud cover`() {
        val hours = listOf(
            hourly("2026-03-14T20:00", WeatherSource.SILURIAN, 40),
            hourly("2026-03-14T21:00", WeatherSource.NWS, 70),
        )

        val selected = CloudCoverViewHandler.selectCloudCoverSource(
            hourlyForecasts = hours,
            requestedSource = WeatherSource.SILURIAN,
            centerTime = LocalDateTime.of(2026, 3, 14, 21, 0),
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(WeatherSource.SILURIAN, selected)
    }

    @Test
    fun `selectCloudCoverSource falls back to source with visible cloud cover`() {
        val hours = listOf(
            hourly("2026-03-14T21:00", WeatherSource.SILURIAN, null),
            hourly("2026-03-14T21:00", WeatherSource.NWS, 65),
            hourly("2026-03-14T22:00", WeatherSource.NWS, 62),
        )

        val selected = CloudCoverViewHandler.selectCloudCoverSource(
            hourlyForecasts = hours,
            requestedSource = WeatherSource.SILURIAN,
            centerTime = LocalDateTime.of(2026, 3, 14, 21, 0),
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(WeatherSource.NWS, selected)
    }

    @Test
    fun `selectCloudCoverSource ignores cloud cover outside visible window`() {
        val hours = listOf(
            hourly("2026-03-16T21:00", WeatherSource.NWS, 65),
            hourly("2026-03-14T21:00", WeatherSource.SILURIAN, null),
        )

        val selected = CloudCoverViewHandler.selectCloudCoverSource(
            hourlyForecasts = hours,
            requestedSource = WeatherSource.SILURIAN,
            centerTime = LocalDateTime.of(2026, 3, 14, 21, 0),
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(WeatherSource.SILURIAN, selected)
    }

    private fun hourly(
        dateTime: String,
        source: WeatherSource,
        cloudCover: Int?,
    ) = HourlyForecastEntity(
        dateTime = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        locationLat = 37.42,
        locationLon = -122.08,
        temperature = 60f,
        condition = "Mostly Clear",
        source = source.id,
        precipProbability = 0,
        cloudCover = cloudCover,
        fetchedAt = 1L,
    )
}
