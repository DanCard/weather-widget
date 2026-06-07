package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TemperatureLabelSuppressionTest {

    @Test
    fun `ACTUAL_HIGH is suppressed when redundant near HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup: HIGH is at index 4 (70.0f)
        // ACTUAL_HIGH is at index 3 (70.2f)
        // Distance is 1, value diff is 0.2f. Should be suppressed.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 4) 70.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 3) 70.2f else 60.0f
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null
        )

        // Verify HIGH (at index 4) is accepted, but ACTUAL_HIGH (at index 3) is NOT.
        assertTrue("HIGH should be accepted at index 4", candidates.any { it.index == 4 && it.role == TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 3 should be suppressed", candidates.any { it.index == 3 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `ACTUAL_HIGH is suppressed when redundant near global HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup: ACTUAL_HIGH is at index 2 (75.0f)
        // FORECAST_HIGH is at index 3 (75.1f)
        // Distance is 1, value diff is 0.1f. Should be suppressed.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 3) 75.1f else 65.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 2) 75.0f else 65.0f
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null
        )

        assertTrue("HIGH should be accepted at index 3", candidates.any { it.index == 3 && it.role == TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 2 should be suppressed by HIGH at index 3", candidates.any { it.index == 2 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `FORECAST_HIGH is suppressed when redundant near ACTUAL_HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup:
        // Global HIGH is at index 9 (80.0f)
        // ACTUAL_HIGH is at index 2 (75.0f)
        // FORECAST_HIGH is at index 3 (75.1f)
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = when(offset) {
                    3 -> 75.1f
                    9 -> 80.0f
                    else -> 60.0f + offset
                },
                label = "${dt.hour}h",
                isActual = offset <= 2,
                actualTemperature = if (offset == 2) 75.0f else 60.0f + offset
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 2, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 2,
            transitionX = 250f,
            observedAt = null
        )

        // ACTUAL_HIGH (at index 2) should be accepted.
        // FORECAST_HIGH (at index 3) should be suppressed by ACTUAL_HIGH.
        assertTrue("ACTUAL_HIGH should be accepted at index 2", candidates.any { it.index == 2 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertFalse("FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 2", candidates.any { it.index == 3 && it.role == TemperatureRole.FORECAST_HIGH })
        assertTrue("HIGH should be accepted at index 9", candidates.any { it.index == 9 && it.role == TemperatureRole.HIGH })
    }

    @Test
    fun `PAST_FORECAST_HIGH is suppressed when redundant near ACTUAL_HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup:
        // Global HIGH is at index 9 (80.0f)
        // ACTUAL_HIGH is at index 4 (75.0f)
        // PAST_FORECAST_HIGH is at index 3 (75.1f)
        // Both are in the "past" (offset <= effectiveActualEndIndex)
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = when(offset) {
                    3 -> 75.1f
                    9 -> 80.0f
                    else -> 60.0f + offset
                },
                label = "${dt.hour}h",
                isActual = offset <= 5,
                actualTemperature = if (offset == 4) 75.0f else 60.0f + offset
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 450f, 5, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 5,
            transitionX = 450f,
            observedAt = null
        )

        assertTrue("ACTUAL_HIGH should be accepted at index 4", candidates.any { it.index == 4 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertFalse("PAST_FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 4", candidates.any { it.index == 3 && it.role == TemperatureRole.PAST_FORECAST_HIGH })
    }

    @Test
    fun `ACTUAL_LOW is retained when near daily low`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Daily/global LOW lives on the forecast curve at index 10 (52.0).
        // ACTUAL_LOW is at index 8 (52.5) — distance 2, value diff 0.5°.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 10) 52.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 9,
                actualTemperature = if (offset == 8) 52.5f else 60.0f
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 350f, 9, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 350f,
            observedAt = null
        )

        println("Candidates: ${candidates.map { "${it.role} idx=${it.index} val=${it.labelTemps[it.index]} raw=${it.rawTemperature}" }}")
        assertTrue("LOW should be accepted at index 10", candidates.any { it.index == 10 && it.role == TemperatureRole.LOW })
        assertTrue("ACTUAL_LOW at index 8 should be retained, not suppressed", candidates.any { it.index == 8 && it.role == TemperatureRole.ACTUAL_LOW })
    }
}
