package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

@Category(ShortDuration::class)
class TemperatureCenterLabelTest {

    @Test
    fun `center label uses actual value when actual line covers temporal midpoint`() {
        val start = LocalDateTime.of(2026, 9, 7, 12, 0)
        val times = listOf(0L, 15L, 60L, 90L, 120L, 180L, 240L)
        val hours = times.mapIndexed { index, minutes ->
            HourData(
                dateTime = start.plusMinutes(minutes),
                temperature = 75f + index,
                label = start.plusMinutes(minutes).toLocalTime().toString(),
                isActual = true,
                actualTemperature = 70f + index / 10f,
            )
        }

        val candidates = candidates(hours, effectiveActualEndIndex = hours.lastIndex)
        val center = candidates.single { it.isCenter }

        assertEquals(start.plusHours(2), hours[center.index].dateTime)
        assertEquals(70.4f, center.labelTemps[center.index], 0.001f)
        assertFalse(center.forceForecastSeries)
    }

    @Test
    fun `center label falls back to forecast when actual line stops before midpoint`() {
        val start = LocalDateTime.of(2026, 9, 8, 12, 0)
        val hours = (0L..4L).map { offset ->
            HourData(
                dateTime = start.plusHours(offset),
                temperature = 80f + offset,
                label = "${offset + 12}",
                isActual = offset < 2,
                actualTemperature = if (offset < 2) 70f + offset else null,
            )
        }

        val candidates = candidates(hours, effectiveActualEndIndex = 1)
        val center = candidates.single { it.isCenter }

        assertEquals(2, center.index)
        assertEquals(82f, center.labelTemps[center.index], 0.001f)
        assertTrue(center.forceForecastSeries)
    }

    @Test
    fun `center label is sorted before extrema labels`() {
        val temps = listOf(70f, 80f, 75f)
        val candidates = mutableListOf(
            TempLabelCandidate(1, TemperatureRole.HIGH, temps, 80f, true),
            TempLabelCandidate(1, TemperatureRole.CENTER, temps, 80f, true, isCenter = true),
        )

        TemperatureLabelResolver.sortLabelCandidates(candidates)

        assertEquals(TemperatureRole.CENTER, candidates.first().role)
    }

    // Pixel 7 Pro 2026-10-01: 7a -> 1a window, 517 px wide, forecast peak 83 at 15:00 and the 16:00
    // midpoint 82. The center label was placed first above the peak and pushed the 83 below the curve.
    @Test
    fun `center label is skipped when the high is drawn one hour from the midpoint`() {
        val hours = forecastDay(peakHour = 15)

        val candidates = candidates(hours, effectiveActualEndIndex = -1, widthPx = 517)

        assertTrue(candidates.none { it.isCenter })
        val high = candidates.single { it.role == TemperatureRole.HIGH }
        assertEquals(15, hours[high.index].dateTime.hour)
    }

    @Test
    fun `center label is kept when the high is far from the midpoint`() {
        val hours = forecastDay(peakHour = 10)

        val candidates = candidates(hours, effectiveActualEndIndex = -1, widthPx = 517)

        val center = candidates.single { it.isCenter }
        assertEquals(16, hours[center.index].dateTime.hour)
    }

    // Desktop 2026-10-03 15:41: 05:00 -> 23:00 window, 1035 px wide (57.5 px/hour). The center
    // landed at 14:00 (~517 px) and drew the actual "86.2" 57 px left of the 15:00 NOW dot's "87.7".
    @Test
    fun `center label is skipped when the NOW dot label is drawn an hour from the midpoint`() {
        val hours = actualRisingToNow(nowHour = 15)

        val candidates = candidates(hours, effectiveActualEndIndex = 10, widthPx = 1035, fetchDotX = 575f, now = hours[10].dateTime, nowX = 575f)

        assertTrue(candidates.none { it.isCenter })
    }

    @Test
    fun `center label is kept when the NOW dot is far from the midpoint`() {
        val hours = actualRisingToNow(nowHour = 15)

        // Same window and data; dot at 08:00 (172.5 px) is ~345 px (33%) from the 14:00 center.
        val candidates = candidates(hours, effectiveActualEndIndex = 10, widthPx = 1035, fetchDotX = 172.5f, now = hours[10].dateTime, nowX = 575f)

        val center = candidates.single { it.isCenter }
        assertEquals(14, hours[center.index].dateTime.hour)
    }

    // The same desktop at 15:50: NOW 91 px from the 14:00 center (beyond the old 64 px budget) and
    // "86.2" still sat beside "88.1". "Near" is the middle half of the graph, not a pixel budget.
    @Test
    fun `center label is skipped anywhere NOW is in the middle half of the graph`() {
        val hours = actualRisingToNow(nowHour = 15)
        // 517.5 = the 14:00 center; 25% of 1035 = 258.75 px either side.
        for (dotX in listOf(608.5f, 517.5f + 258f, 517.5f - 258f)) {
            val candidates = candidates(hours, effectiveActualEndIndex = 10, widthPx = 1035, fetchDotX = dotX, now = hours[10].dateTime, nowX = 575f)
            assertTrue("dotX=$dotX", candidates.none { it.isCenter })
        }
        val outside = candidates(hours, effectiveActualEndIndex = 10, widthPx = 1035, fetchDotX = 517.5f + 260f, now = hours[10].dateTime, nowX = 575f)
        assertEquals(1, outside.count { it.isCenter })
    }

    /**
     * 05:00 -> 23:00. Actual rises monotonically to [nowHour] (an edge, never an extremum); the
     * forecast peaks at 18:00, far enough from the 14:00 center that NEAR_EXTREMUM cannot fire.
     */
    private fun actualRisingToNow(nowHour: Int): List<HourData> {
        val start = LocalDateTime.of(2026, 10, 3, 5, 0)
        return (0L..18L).map { offset ->
            val time = start.plusHours(offset)
            val forecast = 92f - 3f * kotlin.math.abs(time.hour - 18)
            val isActual = time.hour <= nowHour
            HourData(
                dateTime = time,
                temperature = forecast,
                label = time.toLocalTime().toString(),
                isActual = isActual,
                actualTemperature = if (isActual) 59f + 2.8f * offset else null,
            )
        }
    }

    /** Hourly forecast 07:00 -> 01:00 next day peaking at [peakHour], lowest at 07:00. */
    private fun forecastDay(peakHour: Int): List<HourData> {
        val start = LocalDateTime.of(2026, 10, 1, 7, 0)
        return (0L..18L).map { offset ->
            val time = start.plusHours(offset)
            val hoursFromPeak = kotlin.math.abs((time.hour.takeIf { it >= 7 } ?: (time.hour + 24)) - peakHour)
            val temp = if (time.hour < peakHour && time.hour >= 7) 58f + 25f * offset / (peakHour - 7) else 83f - 2f * hoursFromPeak
            HourData(
                dateTime = time,
                temperature = if (offset == 0L) 58f else temp.coerceAtLeast(60f),
                label = time.toLocalTime().toString(),
                isActual = false,
            )
        }
    }

    private fun candidates(
        hours: List<HourData>,
        effectiveActualEndIndex: Int,
        widthPx: Int = 800,
        fetchDotX: Float? = null,
        // Production passes the fetch time and NOW's x; with them today is an incomplete day and the
        // actual line's right edge (NOW) is not an extremum, as in the 2026-10-03 desktop render.
        now: LocalDateTime? = null,
        nowX: Float? = null,
    ): List<TempLabelCandidate> {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(
            hours = hours,
            transitionX = nowX,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchTime = now,
            useCelsius = false,
        )
        return TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effectiveActualEndIndex,
            transitionX = nowX,
            observedAt = null,
            numColumns = 5,
            widthPx = widthPx,
            fetchDotX = fetchDotX,
            useCelsius = false,
        )
    }
}
