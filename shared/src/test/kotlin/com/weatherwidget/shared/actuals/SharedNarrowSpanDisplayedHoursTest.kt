package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The hour marks [ActualTemperatureSeriesBuilder.build] emits must cover the configured NARROW span.
 *
 * This is the platform-independent half of the "Hourly Zoom says 8, the graph shows 8" promise, and
 * it belongs here because this builder is the code Android and desktop genuinely share —
 * `TemperatureHourDataBuilder` (Android) and `TemperatureGraph` (desktop) both call it for the same
 * window. Desktop's [NarrowZoomSpanDisplayedHoursTest] and Android's HourlyZoomSpanSettingRoboTest
 * cover the per-platform chain from the setting down to here; neither could catch a shared
 * off-by-one, since both inherit it.
 *
 * The assertion is **elapsed coverage** — last mark minus first — not the number of marks. A window
 * spanning `start..start+n` is `n` hours wide and needs `n + 1` marks; emitting `n` marks covers
 * `n - 1` hours. That is precisely the defect this test was written for: the top-hour loop ran
 * `while (currentHour.isBefore(endHour))`, dropping the end mark, so an 8h setting drew `12a…7a` and
 * the widget showed 7 hours of weather. Desktop had hit the same off-by-one one layer up in its own
 * point filter and fixed it there ("6h rendered a 5h graph"); this end of it survived because the
 * builder's own actuals filter is already inclusive (`!obsTime.isAfter(endHour)`) — the two halves
 * of one window disagreed.
 */
@Category(ShortDuration::class)
class SharedNarrowSpanDisplayedHoursTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Top of the hour and in the past, so the window math is offset-only. */
    private val centerTime = LocalDateTime.of(2026, 3, 15, 12, 0)

    @Test
    fun `narrow span of eight emits eight hours of marks`() {
        assertCoveredHours(spanHours = 8)
    }

    @Test
    fun `every configurable narrow span emits exactly the hours it promises`() {
        (HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS).forEach { span ->
            assertCoveredHours(spanHours = span)
        }
    }

    @Test
    fun `the emitted window is symmetric — both edge marks belong to the view`() {
        // The start mark was always included; asserting the end mark by name pins down which edge was
        // missing, so a future regression reads as "lost the last hour" rather than "count is off".
        val window = ZoomStage.NARROW.window(8)
        val marks = topHourMarks(window.backHours, window.forwardHours)

        assertEquals(centerTime.minusHours(window.backHours), marks.first())
        assertEquals(centerTime.plusHours(window.forwardHours), marks.last())
    }

    private fun assertCoveredHours(spanHours: Int) {
        val window = ZoomStage.NARROW.window(spanHours)
        val marks = topHourMarks(window.backHours, window.forwardHours)
        val covered = java.time.Duration.between(marks.first(), marks.last()).toHours()

        assertEquals(
            "a ${spanHours}h span must emit ${spanHours}h of marks, got ${marks.size} marks " +
                "${marks.first()}..${marks.last()}",
            spanHours.toLong(),
            covered,
        )
    }

    /** The top-of-hour marks the graph draws its axis from. */
    private fun topHourMarks(backHours: Long, forwardHours: Long): List<LocalDateTime> =
        ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = sampleHourlyForecasts(),
            observations = emptyList(),
            centerTime = centerTime,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = backHours,
            forwardHours = forwardHours,
            contextLookbackHours = 12,
            contextLookaheadHours = 12,
            now = centerTime,
            zoneId = zone,
        ).points.map {
            java.time.Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDateTime()
        }

    private fun sampleHourlyForecasts(count: Int = 48): List<HourlyForecast> {
        val base = LocalDateTime.of(2026, 3, 15, 0, 0)
        return (0 until count).map { hourIndex ->
            HourlyForecast(
                dateTime = base.plusHours(hourIndex.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 50f + hourIndex,
                condition = "Partly Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = 10,
                cloudCover = 55,
                fetchedAt = 1L,
                locationLat = LAT,
                locationLon = LON,
            )
        }
    }

    private companion object {
        const val LAT = 37.42
        const val LON = -122.08
    }
}
