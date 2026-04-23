package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(MediumDuration::class)
class CloudCoverViewHandlerTest {

    @Test
    fun `wide zoom uses three cloud cover smoothing iterations`() {
        assertEquals(3, CloudCoverViewHandler.smoothingIterationsFor(ZoomLevel.WIDE))
    }

    @Test
    fun `narrow zoom reduces cloud cover smoothing to zero iterations`() {
        assertEquals(0, CloudCoverViewHandler.smoothingIterationsFor(ZoomLevel.NARROW))
    }

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

    @Test
    fun `buildCloudHourDataList returns empty when source has null cloud cover`() {
        // This is the race condition scenario: hourlyForecasts is non-empty,
        // but the selected source has no cloud cover data, producing an empty
        // hour list that would render as a black bitmap.
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS, null),
            hourly("2026-03-14T19:00", WeatherSource.NWS, null),
            hourly("2026-03-14T20:00", WeatherSource.NWS, null),
        )

        val result = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals("non-empty input but null cloud cover should yield empty output", 0, result.size)
    }

    @Test
    fun `buildCloudHourDataList returns empty when data is outside time window`() {
        // Data exists but is far outside the zoom window
        val hours = listOf(
            hourly("2026-03-20T18:00", WeatherSource.NWS, 50),
            hourly("2026-03-20T19:00", WeatherSource.NWS, 60),
        )

        val result = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals("data outside zoom window should yield empty output", 0, result.size)
    }

    @Test
    fun `cloud cover body tap uses zoom offset for clear temperature-home zone`() {
        val offset = WeatherWidgetProvider.zoneIndexToOffset(
            zoneIndex = 6,
            currentHourlyOffset = 0,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(0, offset)
    }

    @Test
    fun `cloud cover body tap uses zoom offset for rainy zone instead of rerouting`() {
        val offset = WeatherWidgetProvider.zoneIndexToOffset(
            zoneIndex = 0,
            currentHourlyOffset = 0,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals(-12, offset)
    }

    @Test
    fun `cloud cover body tap preserves narrow zoom offset calculation`() {
        val offset = WeatherWidgetProvider.zoneIndexToOffset(
            zoneIndex = 12,
            currentHourlyOffset = 5,
            zoom = ZoomLevel.NARROW,
        )

        assertEquals(7, offset)
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
