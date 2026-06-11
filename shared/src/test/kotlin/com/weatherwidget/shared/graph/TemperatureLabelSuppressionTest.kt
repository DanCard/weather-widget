package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TemperatureLabelSuppressionTest {

    @Test
    fun `ACTUAL_HIGH is retained when near HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Setup: HIGH is at index 4 (70.0f)
        // ACTUAL_HIGH is at index 3 (70.2f)
        // Distance is 1, value diff is 0.2f. The observed high is always worth its own label,
        // so both should be retained (the user wants forecast vs actual side by side).
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

        // Verify both HIGH (at index 4) and ACTUAL_HIGH (at index 3) are accepted.
        assertTrue("HIGH should be accepted at index 4", candidates.any { it.index == 4 && it.role == TemperatureRole.HIGH })
        assertTrue("ACTUAL_HIGH at index 3 should be retained, not suppressed", candidates.any { it.index == 3 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `ACTUAL_HIGH is retained when near global HIGH`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Setup: ACTUAL_HIGH is at index 2 (75.0f)
        // HIGH (global, on the forecast curve) is at index 3 (75.1f)
        // Distance is 1, value diff is 0.1f. The observed high is always retained.
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
        assertTrue("ACTUAL_HIGH at index 2 should be retained near HIGH at index 3", candidates.any { it.index == 2 && it.role == TemperatureRole.ACTUAL_HIGH })
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
    fun `coincident actual high gets its own label distinct from the forecast high`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Forecast and observed both peak on the SAME hour (index 5) but with different values:
        // forecast 88.0, observed 90.0. Both highs should be labeled (the index-keyed pipeline
        // would otherwise emit only the forecast-valued HIGH and hide the observed peak).
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 5) 88.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 5) 90.0f else 60.0f,
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

        assertTrue("HIGH (forecast 88) should be labeled at index 5", candidates.any { it.index == 5 && it.role == TemperatureRole.HIGH })
        val actualHigh = candidates.find { it.index == 5 && it.role == TemperatureRole.ACTUAL_HIGH }
        assertTrue("ACTUAL_HIGH (observed 90) should also be labeled at index 5", actualHigh != null)
        assertEquals("ACTUAL_HIGH should carry the observed value", 90.0f, actualHigh!!.labelTemps[actualHigh.index], 0.01f)
    }

    @Test
    fun `coincident actual high is not duplicated when it equals the forecast high`() {
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Forecast and observed peak on the same hour with the SAME value -> a second label would
        // be pure noise, so only the single HIGH should be emitted.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 5) 88.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 5) 88.0f else 60.0f,
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

        assertTrue("HIGH should be labeled at index 5", candidates.any { it.index == 5 && it.role == TemperatureRole.HIGH })
        assertTrue("No extra ACTUAL_HIGH when the values match", candidates.none { it.index == 5 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    // Reproduces the Samsung flat-curve case: the right-edge END forecast label was suppressed as
    // "redundant" against an ACTUAL_LOW 3 hours to its left, even though on a zoomed-in day view the
    // two are ~85px apart and clearly distinct. Builds a 21-hour, gently-flat curve where the only
    // extremum within reach of END@20 is ACTUAL_LOW@17 (value within 2° of END).
    private fun samsungFlatCurveHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 11, 2, 0)
        return (0 until 21).map { offset ->
            val dt = start.plusHours(offset.toLong())
            val forecast = when (offset) {
                0 -> 65.0f   // START
                4 -> 68.0f   // daily HIGH (left side, like the screenshot)
                8 -> 61.0f   // daily forecast LOW, far from END
                20 -> 63.0f  // END: flat-ish, within 2° of the actual low at idx 17
                else -> 64.0f
            }
            HourData(
                dateTime = dt,
                temperature = forecast,
                label = "${dt.hour}h",
                // Only index 20 is future, so the future region introduces no extra nearby extremum.
                isActual = offset <= 19,
                actualTemperature = when (offset) {
                    8 -> 64.0f   // actual diverges from the forecast dip so the actual low is NOT here
                    17 -> 62.6f  // ACTUAL_LOW — the candidate that suppressed END on-device
                    else -> forecast
                },
            )
        }
    }

    @Test
    fun `END is retained on a zoomed-in view where the nearby extremum is far in pixels`() {
        val hours = samsungFlatCurveHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 19, null)

        // Full widget width (~567px over 20h => ~28px/hour): ACTUAL_LOW@17 is ~3h ≈ 85px away, so
        // the zoom-aware window (≈2) no longer treats END@20 as redundant.
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 19,
            transitionX = null,
            observedAt = null,
            widthPx = 567,
        )

        assertTrue(
            "END at index 20 should be labeled on a zoomed-in view",
            candidates.any { it.index == 20 && it.role == TemperatureRole.END },
        )
    }

    @Test
    fun `END is still suppressed as redundant when the view is compressed`() {
        val hours = samsungFlatCurveHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 19, null)

        // Same data, narrow width (~120px over 20h => ~6px/hour): now 3h ≈ 18px, so ACTUAL_LOW@17
        // and END@20 genuinely read as a redundant pair and END is decluttered. Proves the window is
        // zoom-aware in BOTH directions, not just "always show END".
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 19,
            transitionX = null,
            observedAt = null,
            widthPx = 120,
        )

        assertTrue("ACTUAL_LOW@17 should remain labeled", candidates.any { it.index == 17 && it.role == TemperatureRole.ACTUAL_LOW })
        assertFalse(
            "END at index 20 should be suppressed as redundant on a compressed view",
            candidates.any { it.index == 20 && it.role == TemperatureRole.END },
        )
    }

    @Test
    fun `monotonic forecast gets a midpoint label so the line is not bare`() {
        val start = LocalDateTime.of(2026, 6, 11, 18, 0)
        // Steady overnight decline: actual region (0..9) ends near now; the forecast region (10..20)
        // is strictly monotonic, so it has no interior extremum and would otherwise show only END.
        val hours = (0 until 21).map { offset ->
            val dt = start.plusHours(offset.toLong())
            val forecast = (75 - offset).toFloat() // 75 -> 55, strictly decreasing
            HourData(
                dateTime = dt,
                temperature = forecast,
                label = "${dt.hour}h",
                isActual = offset <= 9,
                actualTemperature = forecast,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 9, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 250f,
            observedAt = null,
        )

        // A forecast-colored label lands strictly inside the future region (between the transition
        // at idx 9 and the END at idx 20), not just at the endpoint.
        val interiorForecast = candidates.filter { it.index in 10..19 && it.forceForecastSeries }
        assertTrue(
            "monotonic forecast should get an interior midpoint label, got ${candidates.map { it.role to it.index }}",
            interiorForecast.isNotEmpty(),
        )
    }

    @Test
    fun `tight-zoom forecast region of three hours still gets a midpoint label`() {
        // Reproduces a live desktop view: 5 hourly points, transition at idx 1, monotonic-rising
        // forecast (idxs 1..4) whose only label is the daily HIGH at the right end. The forecast line
        // should still get a value label in its middle (idx 2).
        val start = LocalDateTime.of(2026, 6, 11, 2, 0)
        val temps = listOf(63.0f, 63.75f, 64.0f, 65.0f, 68.0f)
        val hours = temps.mapIndexed { offset, t ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = t,
                label = "${dt.hour}h",
                isActual = offset <= 1,
                actualTemperature = t,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 100f, 1, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 1,
            transitionX = 100f,
            observedAt = null,
        )

        assertTrue(
            "3-hour forecast region should get an interior midpoint label, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 2 && it.role == TemperatureRole.LOCAL && it.forceForecastSeries },
        )
    }

    @Test
    fun `forecast region with its own extremum gets no extra midpoint label`() {
        val start = LocalDateTime.of(2026, 6, 11, 18, 0)
        // Forecast region (10..20) has a real interior valley at idx 15, so no synthetic midpoint.
        val hours = (0 until 21).map { offset ->
            val dt = start.plusHours(offset.toLong())
            val forecast = when {
                offset <= 9 -> (75 - offset).toFloat()
                offset == 15 -> 50.0f          // forecast valley (interior extremum)
                else -> 66.0f
            }
            HourData(
                dateTime = dt,
                temperature = forecast,
                label = "${dt.hour}h",
                isActual = offset <= 9,
                actualTemperature = forecast,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 9, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 250f,
            observedAt = null,
        )

        // The midpoint injection only fills a BARE forecast region; idx 15 already covers the middle,
        // so no LOCAL label should be synthesized at the region's geometric midpoint (idx 14/15).
        assertTrue("forecast valley at idx 15 should be labeled", candidates.any { it.index == 15 })
        val syntheticMidpoints = candidates.filter { it.role == TemperatureRole.LOCAL && it.index in 10..13 }
        assertTrue("no synthetic midpoint when the region already has an interior label", syntheticMidpoints.isEmpty())
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
