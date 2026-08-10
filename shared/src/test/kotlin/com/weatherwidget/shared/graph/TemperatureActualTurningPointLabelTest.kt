package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
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

    /**
     * The 2026-08-09 desktop window, reduced: an afternoon plateau whose observed line reverses by more
     * than ACTUAL_TURN_REVERSAL_DEGREES (0.75°F) five times inside 1.6°F. Every one of those turns is
     * "prominent" by the hysteresis rule, and before the thinning all five were labeled — stacked on
     * top of each other into unreadable mush.
     *
     * The fallback only runs when the day has no confirmed daily extreme, which is the real situation
     * here: today's high is not "reached" while the remaining forecast still climbs well above the
     * observed max, and the day's coldest sample sits on the window edge.
     */
    @Test
    fun `plateau with many prominent turns yields one actual high and one actual low`() {
        val hours = plateauHours()

        val candidates = candidates(hours, effectiveActualEndIndex = 13)

        val highs = candidates.filter { it.role == TemperatureRole.ACTUAL_HIGH }
        val lows = candidates.filter { it.role == TemperatureRole.ACTUAL_LOW }
        assertEquals(
            "plateau must yield exactly one actual high, got ${highs.map { it.index }}",
            1,
            highs.size,
        )
        assertEquals(
            "plateau must yield exactly one actual low, got ${lows.map { it.index }}",
            1,
            lows.size,
        )
        // …and it must be the most extreme turn, not merely the first or last one found.
        assertEquals("kept high should be the warmest turn (77.35)", 11, highs.single().index)
        assertEquals("kept low should be the coldest turn (75.84)", 5, lows.single().index)
    }

    /** Guards the reduction: without it this fixture really does produce five stacked labels. */
    @Test
    fun `plateau fixture would offer five prominent turns before thinning`() {
        val hours = plateauHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(
            hours = hours,
            transitionX = 1_000f,
            effectiveActualEndIndex = 13,
            fetchTime = null,
            useCelsius = false,
        )
        assertEquals(
            "fixture should offer 3 prominent highs to thin: ${extrema.actualProminentHighIndices}",
            listOf(3, 7, 11),
            extrema.actualProminentHighIndices,
        )
        assertEquals(
            "fixture should offer 2 prominent lows to thin: ${extrema.actualProminentLowIndices}",
            listOf(5, 9),
            extrema.actualProminentLowIndices,
        )
        assertTrue(
            "fixture must have no confirmed daily actual high, or the fallback never runs",
            extrema.actualDailyHighIndices.isEmpty(),
        )
        assertTrue(
            "fixture must have no confirmed daily actual low, or the fallback never runs",
            extrema.actualDailyLowIndices.isEmpty(),
        )
    }

    private fun plateauHours(): List<HourData> {
        // Reversals: 76.64->75.84 (0.80), ->77.25 (1.41), ->76.14 (1.11), ->77.35 (1.21), ->76.5 (0.85).
        // All clear 0.75, so all five turns register. Coldest sample (70) is the left edge, so the day
        // gets no daily low; the forecast tail at 86° keeps today's high "not yet reached".
        val actual = listOf(
            70f, 72f, 74f, 76.64f, 76.2f, 75.84f, 76.5f,
            77.25f, 76.7f, 76.14f, 76.8f, 77.35f, 77.0f, 76.5f,
        )
        val start = LocalDateTime.of(2026, 8, 9, 13, 0)
        val observed = actual.indices.map { index ->
            val dateTime = start.plusMinutes(index * 5L)
            HourData(
                dateTime = dateTime,
                temperature = 80f,
                label = dateTime.toLocalTime().toString(),
                isActual = true,
                actualTemperature = actual[index],
            )
        }
        val future = (1..4).map { step ->
            val dateTime = start.plusMinutes((actual.size - 1 + step) * 5L)
            HourData(
                dateTime = dateTime,
                temperature = 86f,
                label = dateTime.toLocalTime().toString(),
                isActual = false,
            )
        }
        return observed + future
    }

    private fun candidates(
        hours: List<HourData>,
        effectiveActualEndIndex: Int = hours.lastIndex,
    ): List<TempLabelCandidate> {
        val lastIndex = effectiveActualEndIndex
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
