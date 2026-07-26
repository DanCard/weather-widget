package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

@Category(ShortDuration::class)
class TemperatureActualTurningPointLabelTest {

    @Test
    fun `historical slice labels broad actual peak and valley but not edges`() {
        val actual = listOf(
            66.95f, 67.4f, 68.2f, 69.3f, 70.4f,
            71.785f, 71.742f, 71.789f, 70.726f,
            71.1f, 71.7f, 72.5f, 73.81f,
        )
        val hours = hours(
            forecast = listOf(63f, 64f, 65f, 66f, 67f, 68f, 69f, 70f, 71f, 72f, 73f, 73f, 73f),
            actual = actual,
        )

        val candidates = candidates(hours)

        assertTrue(
            "broad actual peak should be labeled: ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.ACTUAL_HIGH && it.index == 7 },
        )
        assertTrue(
            "following actual valley should be labeled: ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.ACTUAL_LOW && it.index == 8 },
        )
        assertFalse(
            "left edge must not become an actual low",
            candidates.any { it.role == TemperatureRole.ACTUAL_LOW && it.index == 0 },
        )
        assertFalse(
            "right edge must not become an actual high",
            candidates.any { it.role == TemperatureRole.ACTUAL_HIGH && it.index == hours.lastIndex },
        )
    }

    @Test
    fun `sub threshold actual wiggles do not create labels`() {
        val actual = listOf(67f, 68f, 69f, 70f, 70.4f, 70.05f, 70.5f, 71f, 72f)
        val hours = hours(
            forecast = listOf(63f, 64f, 65f, 66f, 67f, 68f, 69f, 70f, 71f),
            actual = actual,
        )

        val candidates = candidates(hours)

        assertFalse(
            "small actual wiggles must not create pink extrema: ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.ACTUAL_HIGH || it.role == TemperatureRole.ACTUAL_LOW },
        )
    }

    private fun candidates(hours: List<HourData>): List<TempLabelCandidate> {
        val lastIndex = hours.lastIndex
        val extrema = TemperatureLabelResolver.computeExtremaIndices(
            hours = hours,
            transitionX = 1_000f,
            effectiveActualEndIndex = lastIndex,
            fetchTime = null,
            useCelsius = false,
        )
        return TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = lastIndex,
            transitionX = 1_000f,
            observedAt = null,
            widthPx = 584,
            useCelsius = false,
        )
    }

    private fun hours(forecast: List<Float>, actual: List<Float>): List<HourData> {
        val start = LocalDateTime.of(2026, 7, 25, 9, 0)
        return forecast.indices.map { index ->
            val dateTime = start.plusMinutes(index * 5L)
            HourData(
                dateTime = dateTime,
                temperature = forecast[index],
                label = dateTime.toLocalTime().toString(),
                isActual = true,
                actualTemperature = actual[index],
            )
        }
    }
}
