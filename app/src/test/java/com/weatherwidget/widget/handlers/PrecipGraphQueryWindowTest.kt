package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.WidgetQueryWindows

import android.app.Application
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Regression tests for the blank precipitation graph bug:
 *
 * Root cause: Tapping a far-future day (e.g., day 6) sets `hourlyOffset` to ~154h.
 * `buildPrecipHourDataList` generates a WIDE window of now+142h..now+166h and does
 * exact epoch-ms lookups. When the hourly data passed in only covered now+96h (the old
 * worker fetch window), all lookups missed → empty hours list → blank transparent bitmap.
 *
 * Fix: Extended `fetchHourlyForecasts` from +96h to +168h in WeatherWidgetWorker, and
 * added `HOURLY_GRAPH_LOOKAHEAD_HOURS = 168L` used by the WeatherWidgetProvider startup path.
 *
 * These tests verify:
 * 1. The 168h constant is large enough to cover the farthest reachable daily tap offset.
 * 2. buildPrecipHourDataList returns non-empty when 168h of data is available for a day-6 tap.
 * 3. buildPrecipHourDataList returns empty when only 96h of data is available (regression check).
 * 4. buildPrecipHourDataList returns empty when data genuinely doesn't exist for that window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(LongDuration::class)
class PrecipGraphQueryWindowTest {

    /**
     * Day 6 at 8 AM is the farthest daily bar a user can tap (7 daily bars, 0-indexed from today).
     * DayClickHelper.calculatePrecipitationOffset returns hours until 8AM of that day.
     * Worst case: tapping at midnight → 6 days × 24h + 8h = 152h, plus WIDE zoom's forwardHours (12h) = 164h.
     * HOURLY_GRAPH_LOOKAHEAD_HOURS must cover this margin.
     */
    @Test
    fun `HOURLY_GRAPH_LOOKAHEAD_HOURS covers farthest reachable daily tap plus WIDE zoom forward window`() {
        val maxDailyOffset = 6 * 24 + 8 // 6 full days + 8h to reach 8AM = 152h
        val wideForwardHours = ZoomLevel.WIDE.forwardHours
        val requiredLookahead = maxDailyOffset + wideForwardHours // 164h

        assertTrue(
            "HOURLY_GRAPH_LOOKAHEAD_HOURS=${WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS} " +
                "must be >= $requiredLookahead (maxDailyOffset=$maxDailyOffset + wideForward=$wideForwardHours)",
            WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS >= requiredLookahead,
        )
    }

    /**
     * HOURLY_GRAPH_LOOKAHEAD_HOURS (graph rendering) must exceed HOURLY_LOOKAHEAD_HOURS (rain analysis).
     * They serve different purposes and must not be accidentally conflated.
     */
    @Test
    fun `HOURLY_GRAPH_LOOKAHEAD_HOURS is larger than HOURLY_LOOKAHEAD_HOURS`() {
        assertTrue(
            "Graph lookahead (${WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS}h) should exceed " +
                "rain analysis lookahead (${WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS}h)",
            WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS > WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS,
        )
    }

    /**
     * Core regression: when hourly data spans now+142h..now+166h (a day-6 WIDE window),
     * buildPrecipHourDataList must return 25 hours (the WIDE window size: 12 back + 1 center + 12 forward).
     * Before the fix, this returned 0 because the worker only stored data up to +96h.
     */
    @Test
    fun `buildPrecipHourDataList returns full window for far-future day tap when 168h data is available`() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val offsetHours = 154L // representative day-6 8AM offset
        val centerTime = now.plusHours(offsetHours)

        // Simulate 168h of hourly data — what the fixed worker provides
        val hourlyForecasts = makeHourlyData(now, startOffsetHours = 0, endOffsetHours = 168)

        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val expectedCount = ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours // 24
        assertEquals(
            "WIDE window around offset ${offsetHours}h should yield $expectedCount hours",
            expectedCount,
            hours.size.toLong(),
        )
    }

    /**
     * Regression: the old worker only fetched up to +96h. With that data, tapping day 6
     * produced a blank graph because buildPrecipHourDataList found no matching hourly rows.
     */
    @Test
    fun `buildPrecipHourDataList returns empty for far-future day tap when data only covers 96h`() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val offsetHours = 154L // day-6 tap
        val centerTime = now.plusHours(offsetHours)

        // Simulate the OLD truncated worker window: only now..now+96h
        val hourlyForecasts = makeHourlyData(now, startOffsetHours = 0, endOffsetHours = 96)

        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertTrue(
            "96h data must NOT cover a day-6 tap at offset ${offsetHours}h — this was the original bug",
            hours.isEmpty(),
        )
    }

    /**
     * Sanity check: when genuinely no hourly data exists at all, the list is empty.
     * This confirms the empty-list path is intentional (no data) vs. a bug (wrong window).
     */
    @Test
    fun `buildPrecipHourDataList returns empty when hourlyForecasts is empty`() {
        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = emptyList(),
            centerTime = LocalDateTime.now(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertTrue("Empty input → empty output", hours.isEmpty())
    }

    /**
     * Near-future tap (day 1, ~24h offset) must work regardless of the fix — verifies that
     * short-offset taps still behave correctly with the extended 168h window.
     */
    @Test
    fun `buildPrecipHourDataList returns full window for near-future day tap`() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val offsetHours = 24L
        val centerTime = now.plusHours(offsetHours)

        val hourlyForecasts = makeHourlyData(now, startOffsetHours = 0, endOffsetHours = 168)

        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hourlyForecasts,
            centerTime = centerTime,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val expectedCount = ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours
        assertEquals(
            "WIDE window around offset ${offsetHours}h should yield $expectedCount hours",
            expectedCount,
            hours.size.toLong(),
        )
    }

    // ---

    private fun makeHourlyData(
        base: LocalDateTime,
        startOffsetHours: Int,
        endOffsetHours: Int,
    ): List<HourlyForecastEntity> {
        val zoneId = ZoneId.systemDefault()
        return (startOffsetHours..endOffsetHours).map { h ->
            HourlyForecastEntity(
                dateTime = base.plusHours(h.toLong()).atZone(zoneId).toInstant().toEpochMilli(),
                locationLat = 37.42,
                locationLon = -122.08,
                temperature = 60f,
                condition = "Partly Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = 20,
                cloudCover = 40,
                fetchedAt = 1L,
            )
        }
    }
}
