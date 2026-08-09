package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.HourlyTouchZoneMapper

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class CloudCoverViewHandlerTest {

    @Test
    fun `wide zoom uses three cloud cover smoothing iterations`() {
        assertEquals(3, CloudCoverViewHandler.smoothingIterationsFor(ZoomStage.WIDE.window()))
    }

    @Test
    fun `narrow zoom reduces cloud cover smoothing to zero iterations`() {
        assertEquals(0, CloudCoverViewHandler.smoothingIterationsFor(ZoomStage.NARROW.window()))
    }

    @Test
    fun `buildWindowHourKeys spans backHours through forwardHours`() {
        val center = LocalDateTime.of(2026, 3, 14, 12, 0)
        val keys = CloudCoverViewHandler.buildWindowHourKeys(center, ZoomStage.WIDE.window())

        val expectedSize = (ZoomStage.WIDE.window().backHours + ZoomStage.WIDE.window().forwardHours).toInt()
        assertEquals(expectedSize, keys.size)

        val expectedStart = center
            .minusHours(ZoomStage.WIDE.window().backHours.toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, keys.min())
    }

    @Test
    fun `buildWindowHourKeys aligns half-hours forward to next whole hour`() {
        val center = LocalDateTime.of(2026, 3, 14, 12, 30)
        val keys = CloudCoverViewHandler.buildWindowHourKeys(center, ZoomStage.WIDE.window())

        // 12:30 should align to 13:00 as the center, so the span is [13 - back, 13 + forward]
        val alignedCenter = LocalDateTime.of(2026, 3, 14, 13, 0)
        val expectedStart = alignedCenter
            .minusHours(ZoomStage.WIDE.window().backHours.toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, keys.min())
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
            zoom = ZoomStage.WIDE.window(),
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
            zoom = ZoomStage.WIDE.window(),
        )

        assertEquals("data outside zoom window should yield empty output", 0, result.size)
    }

    @Test
    fun `cloud cover body tap uses zoom offset for clear temperature-home zone`() {
        val offset = HourlyTouchZoneMapper.zoneIndexToOffset(
            zoneIndex = 6,
            currentHourlyOffset = 0,
            zoom = ZoomStage.WIDE.window(),
        )

        assertEquals(0, offset)
    }

    @Test
    fun `cloud cover body tap uses zoom offset for rainy zone instead of rerouting`() {
        val offset = HourlyTouchZoneMapper.zoneIndexToOffset(
            zoneIndex = 0,
            currentHourlyOffset = 0,
            zoom = ZoomStage.WIDE.window(),
        )

        assertEquals(-12, offset)
    }

    @Test
    fun `cloud cover body tap preserves narrow zoom offset calculation`() {
        val offset = HourlyTouchZoneMapper.zoneIndexToOffset(
            zoneIndex = 12,
            currentHourlyOffset = 5,
            zoom = ZoomStage.NARROW.window(),
        )

        assertEquals(7, offset)
    }

    @Test
    fun `buildCloudHourDataList uses 6-hour interval on narrow widgets in WIDE zoom`() {
        val now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hours = (0..24).map { i ->
            hourly(now.plusHours(i.toLong() - 12).toString(), WeatherSource.NWS, 50)
        }

        // Narrow widget (e.g. 5 columns)
        val result = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = hours,
            centerTime = now,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
        )

        val labeledHours = result.filter { it.showLabel }
        // For WIDE zoom (25 points total: -12 to +12), with 6-hour interval:
        // Indices: 0, 6, 12, 18, 24 should be labeled (if we start from index 0)
        // Note: buildCloudHourDataList also labels the "closest to now" hour.
        
        // Let's check the distance between labeled hours (excluding the 'Now' one if it's an outlier)
        val intervals = labeledHours.map { it.dateTime }
            .zipWithNext { a, b -> java.time.Duration.between(a, b).toHours() }
            .filter { it > 0 }
            .distinct()
        
        // We expect 6-hour intervals (and potentially a smaller one if 'Now' is forced)
        assert(intervals.contains(6L)) { "Expected 6-hour intervals in narrow widget WIDE zoom, but got $intervals" }
        assert(!intervals.contains(4L)) { "Did not expect 4-hour intervals in narrow widget WIDE zoom" }
    }

    @Test
    fun `buildCloudHourDataList uses 4-hour interval on wide widgets in WIDE zoom`() {
        val now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hours = (0..24).map { i ->
            hourly(now.plusHours(i.toLong() - 12).toString(), WeatherSource.NWS, 50)
        }

        // Wide widget (e.g. 8 columns)
        val result = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = hours,
            centerTime = now,
            numColumns = 8,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
        )

        val labeledHours = result.filter { it.showLabel }
        val intervals = labeledHours.map { it.dateTime }
            .zipWithNext { a, b -> java.time.Duration.between(a, b).toHours() }
            .filter { it > 0 }
            .distinct()
        
        assert(intervals.contains(4L)) { "Expected 4-hour intervals in wide widget WIDE zoom, but got $intervals" }
        assert(!intervals.contains(6L)) { "Did not expect 6-hour intervals in wide widget WIDE zoom" }
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
