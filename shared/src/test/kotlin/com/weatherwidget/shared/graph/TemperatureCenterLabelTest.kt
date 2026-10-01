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
    ): List<TempLabelCandidate> {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(
            hours = hours,
            transitionX = null,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchTime = null,
            useCelsius = false,
        )
        return TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effectiveActualEndIndex,
            transitionX = null,
            observedAt = null,
            numColumns = 5,
            widthPx = widthPx,
            useCelsius = false,
        )
    }
}
