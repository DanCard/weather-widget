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

    private fun candidates(
        hours: List<HourData>,
        effectiveActualEndIndex: Int,
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
            widthPx = 800,
            useCelsius = false,
        )
    }
}
