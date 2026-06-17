package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.cos

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
        // Start late so the observation cutoff (index 2) is the last hour of its day: the actual-high
        // day is then COMPLETE (no higher same-day forecast remains), so the incomplete-day rule does
        // not drop the ACTUAL_HIGH, and the global HIGH (index 9, 80°) falls on the next day.
        val start = LocalDateTime.of(2026, 4, 8, 21, 0)

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
        // Start late so the observation cutoff (index 5) is the last hour of its day: the actual-high
        // day is then COMPLETE (no higher same-day forecast remains), so the incomplete-day rule does
        // not drop the ACTUAL_HIGH, and the global HIGH (index 9, 80°) falls on the next day.
        val start = LocalDateTime.of(2026, 4, 8, 18, 0)

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
    fun `forecast plateau equal to the daily high gets no duplicate midpoint label`() {
        // Reproduces the live Samsung (SM-F936U1) bug: the forecast curve is a flat 88° plateau that
        // declines only at the very end (88..88, 86, 85). The geometric midpoint of the future region
        // lands on the plateau, so its value (88) equals the global HIGH already labeled at idx 0 —
        // without the value-redundancy guard the line renders two "88°" labels.
        val start = LocalDateTime.of(2026, 6, 12, 14, 0)
        val forecastTemps = (0 until 18).map { offset ->
            when (offset) {
                16 -> 86.0f
                17 -> 85.0f
                else -> 88.0f // flat plateau across past-forecast + most of the future region
            }
        }
        val hours = forecastTemps.mapIndexed { offset, t ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = t,
                label = "${dt.hour}h",
                isActual = offset <= 14,
                // Observed line runs well below the forecast plateau, so no actual point reads "88".
                actualTemperature = if (offset <= 14) 81.0f else t,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 248f, 14, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 14,
            transitionX = 248f,
            observedAt = null,
            widthPx = 567,
        )

        // The daily HIGH (88) is still labeled once.
        assertTrue(
            "daily HIGH at 88 should be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.HIGH && TemperatureLabelResolver.formatTemp(it.labelTemps[it.index]) == "88" },
        )
        // No synthetic forecast midpoint is injected inside the future region (idx 15..16).
        assertFalse(
            "no synthetic midpoint on a plateau that repeats the daily high, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.LOCAL && it.forceForecastSeries && it.index in 15..16 },
        )
        // And crucially: exactly one forecast-line label reads "88" — no duplicate.
        val eightyEightLabels = candidates.filter {
            it.role in setOf(
                TemperatureRole.HIGH, TemperatureRole.LOW,
                TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
                TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
                TemperatureRole.START, TemperatureRole.END, TemperatureRole.LOCAL,
            ) && TemperatureLabelResolver.formatTemp(it.labelTemps[it.index]) == "88"
        }
        assertEquals(
            "exactly one forecast label should read 88, got ${eightyEightLabels.map { it.role to it.index }}",
            1, eightyEightLabels.size,
        )
    }

    @Test
    fun `actual low on the right-edge index wins over END`() {
        // Reproduces the live emulator bug (2026-06-16): the observed line keeps cooling into the
        // evening, so the absolute actual low lands on the last (right-edge / NOW) index — which is
        // also the END boundary. resolveExtremaRole used to test hours.lastIndex (END) before the
        // actual-extrema checks, so END won and the right-edge label showed the forecast endpoint
        // value (70) instead of the observed low (59). The label appeared to "vanish" whenever the
        // coldest observed point happened to land on the edge.
        val start = LocalDateTime.of(2026, 4, 8, 0, 0) // midnight -> all 24 hours are one calendar day
        // Forecast curve: low at idx 3 (54), high at idx 14 (79), END value 70 at idx 23.
        val forecast = listOf(
            60f, 58f, 56f, 54f, 57f, 60f, 63f, 66f, 69f, 72f, 74f, 76f,
            77f, 78f, 79f, 78f, 77f, 76f, 75f, 74f, 73f, 72f, 71f, 70f,
        )
        // Observed line: peaks at idx 10 (72), then descends to its unique global minimum 59 at the
        // right-edge index 23.
        val actual = listOf(
            62f, 63f, 64f, 65f, 66f, 67f, 68f, 69f, 70f, 71f, 72f, 71f,
            70f, 69f, 68f, 67f, 66f, 65f, 64f, 63f, 62f, 61f, 60f, 59f,
        )
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = forecast[offset],
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actual[offset],
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        val rightEdge = candidates.find { it.index == 23 }
        assertEquals(
            "right-edge label should be ACTUAL_LOW, not END, got ${candidates.map { it.role to it.index }}",
            TemperatureRole.ACTUAL_LOW, rightEdge?.role,
        )
        assertEquals(
            "right-edge label should carry the observed low (59), not the forecast endpoint (70)",
            59.0f, rightEdge!!.labelTemps[rightEdge.index], 0.01f,
        )
    }

    @Test
    fun `actual low on the fetch-dot right edge wins over END`() {
        // Current-day variant of the bug: the right-edge index is NOW, so the fetch dot lands on it.
        // checkFetchDotSuppression used to relabel any edge fetch-dot index to START/END before the
        // role mattered, clobbering the ACTUAL_LOW. The actual-extrema exemption must keep the observed
        // low (59) instead of the forecast endpoint (70).
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        val forecast = listOf(
            60f, 58f, 56f, 54f, 57f, 60f, 63f, 66f, 69f, 72f, 74f, 76f,
            77f, 78f, 79f, 78f, 77f, 76f, 75f, 74f, 73f, 72f, 71f, 70f,
        )
        val actual = listOf(
            62f, 63f, 64f, 65f, 66f, 67f, 68f, 69f, 70f, 71f, 72f, 71f,
            70f, 69f, 68f, 67f, 66f, 65f, 64f, 63f, 62f, 61f, 60f, 59f,
        )
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = forecast[offset],
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actual[offset],
            )
        }

        // fetchTime = the last hour => fetchIdx lands on the right-edge index (23), the NOW dot.
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, hours.last().dateTime)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = 1_000L, // non-null => the fetch-dot path is active
            widthPx = 600,
        )

        val rightEdge = candidates.find { it.index == 23 }
        assertEquals(
            "right-edge fetch-dot label should be ACTUAL_LOW, not END, got ${candidates.map { it.role to it.index }}",
            TemperatureRole.ACTUAL_LOW, rightEdge?.role,
        )
        assertEquals(
            "right-edge label should carry the observed low (59), not the forecast endpoint (70)",
            59.0f, rightEdge!!.labelTemps[rightEdge.index], 0.01f,
        )
    }

    @Test
    fun `absolute actual low just inside the right edge is not endpoint-suppressed`() {
        // Reproduces the live Samsung (SM-F936U1) case: the observed line cools toward NOW but the
        // data carries a short forecast tail, so the absolute observed low (idx 57) lands a couple of
        // points shy of the right edge (idx 59) — inside checkEndpointSuppression's edge window. It
        // used to be decluttered away (LabelSuppressed reason=ENDPOINT). The absolute low must survive.
        val start = LocalDateTime.of(2026, 6, 13, 0, 0)
        val n = 60
        val effEnd = 57 // observed 0..57; a 2-point forecast tail (58..59) follows
        val hours = (0 until n).map { i ->
            val dt = start.plusHours(i.toLong())
            // Forecast arch peaks at idx 30; its daily low sits at the left, well away from idx 57.
            val f = 78f - 0.3f * kotlin.math.abs(i - 30)
            // Observed arch peaks at idx 20 and keeps descending, so its UNIQUE global minimum is the
            // observation cutoff at idx 57 (the absolute observed low).
            val a = if (i <= effEnd) 75f - 0.35f * kotlin.math.abs(i - 20) else f
            HourData(
                dateTime = dt,
                temperature = f,
                label = "${dt.hour}h",
                isActual = i <= effEnd,
                actualTemperature = a,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 560f, effEnd, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effEnd,
            transitionX = 560f,
            observedAt = null,
            widthPx = 567,
        )

        assertTrue(
            "the absolute observed low at idx 57 must be labeled, not edge-suppressed; got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 57 && it.role == TemperatureRole.ACTUAL_LOW },
        )
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

    // A 48h, fully-observed two-day region with one valley (4am) and one peak (4pm) per day.
    // Day 0 is colder/cooler than day 1, and the forecast runs 3° warmer than the actuals, so each
    // day's actual extreme reads as a distinct value. This exercises both paths: the per-day
    // membership path (day 1's extreme, at a plain interior index) and the coincident injection
    // (day 0's extreme, which also happens to be the global daily forecast low/high index).
    private fun twoDayObservedHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 10, 0, 0)
        return (0 until 48).map { i ->
            val dt = start.plusHours(i.toLong())
            val hourOfDay = i % 24
            val dailyMin = if (i < 24) 50.0 else 58.0
            val theta = (hourOfDay - 4) / 24.0 * 2 * Math.PI
            val actual = (dailyMin + 10.0 * (1 - cos(theta))).toFloat() // min at 4am, peak at 4pm
            HourData(
                dateTime = dt,
                temperature = actual + 3f,
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actual,
            )
        }
    }

    @Test
    fun `each day in a multi-day actual region gets its own actual low label`() {
        val hours = twoDayObservedHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        // Regression: previously only the single globally-coldest valley got an ACTUAL_LOW; the
        // second day's actual low (idx 28) went unlabeled.
        assertTrue("day 0 actual low should be labeled at idx 4", candidates.any { it.index == 4 && it.role == TemperatureRole.ACTUAL_LOW })
        assertTrue("day 1 actual low should be labeled at idx 28", candidates.any { it.index == 28 && it.role == TemperatureRole.ACTUAL_LOW })
    }

    @Test
    fun `each day in a multi-day actual region gets its own actual high label`() {
        val hours = twoDayObservedHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        assertTrue("day 0 actual high should be labeled at idx 16", candidates.any { it.index == 16 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertTrue("day 1 actual high should be labeled at idx 40", candidates.any { it.index == 40 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `per-day actual low on a day-boundary slope is not labeled`() {
        val start = LocalDateTime.of(2026, 6, 10, 0, 0)
        // One overnight valley that straddles midnight: the actual series is a V with its bottom at
        // idx 27 (just after the Tue->Wed boundary at idx 24). Tue's calendar-day minimum is therefore
        // a still-descending slope point at idx 23, NOT a real valley — it must not get its own label
        // (the genuine valley is owned by Wed at idx 27). Forecast runs 3° warmer than the actuals.
        val hours = (0 until 48).map { i ->
            val dt = start.plusHours(i.toLong())
            val actual = 55f + kotlin.math.abs(i - 27) * 0.8f
            HourData(
                dateTime = dt,
                temperature = actual + 3f,
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actual,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        assertTrue("the real overnight valley at idx 27 should be labeled", candidates.any { it.index == 27 && it.role == TemperatureRole.ACTUAL_LOW })
        assertFalse("the day-boundary slope point at idx 23 should NOT get an actual-low label", candidates.any { it.index == 23 && it.role == TemperatureRole.ACTUAL_LOW })
    }

    // Like the smooth-V case above, but the ACTUAL series is JAGGED: a 1-sample notch makes the
    // pre-midnight shoulder a genuine local min, so the immediate-neighbour isActualLocalMin guard
    // no longer drops it (this is the real on-device failure: emulator idx 162 = 66.9°). The forecast
    // (label) series stays smooth, so the shoulder is not a significant LOCAL extremum either — its
    // only label would be the spurious per-day ACTUAL_LOW. The same-type-without-separator pass in
    // TemperatureExtrema must drop it while keeping both real overnight valleys (idx 27, idx 51),
    // which are separated by afternoon highs.
    private fun jaggedMidnightStraddleHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 10, 0, 0)
        // Control points (idx -> base temp): afternoon highs at 12/36/60, overnight valleys just
        // after the midnight boundaries at idx 27 (day1) and idx 51 (day2). Monotonic in between.
        val controls = listOf(0 to 64f, 12 to 88f, 27 to 60f, 36 to 90f, 51 to 61f, 60 to 89f, 71 to 70f)
        fun base(i: Int): Float {
            val hi = controls.indexOfFirst { it.first >= i }.coerceAtLeast(1)
            val (x0, y0) = controls[hi - 1]
            val (x1, y1) = controls[hi]
            return y0 + (y1 - y0) * (i - x0).toFloat() / (x1 - x0)
        }
        val actualArr = FloatArray(72) { base(it) }
        // Jagged notch: dip day0's last pre-midnight point just below both neighbours so it reads as
        // a real local min (the genuine valley is idx 27, owned by day1 after the calendar boundary).
        actualArr[23] = minOf(actualArr[22], actualArr[24]) - 0.5f
        return (0 until 72).map { i ->
            val dt = start.plusHours(i.toLong())
            HourData(
                dateTime = dt,
                temperature = base(i) + 3f, // forecast/label series stays smooth (no notch)
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actualArr[i],
            )
        }
    }

    @Test
    fun `jagged midnight-straddle shoulder is not labeled as an actual low`() {
        val hours = jaggedMidnightStraddleHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 71, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 71,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        assertFalse(
            "the jagged pre-midnight shoulder at idx 23 must NOT get an actual-low label",
            candidates.any { it.index == 23 && it.role == TemperatureRole.ACTUAL_LOW }
        )
        assertTrue(
            "the real day1 overnight valley at idx 27 should be labeled",
            candidates.any { it.index == 27 && it.role == TemperatureRole.ACTUAL_LOW }
        )
        assertTrue(
            "the separated day2 overnight valley at idx 51 should be retained",
            candidates.any { it.index == 51 && it.role == TemperatureRole.ACTUAL_LOW }
        )
    }

    @Test
    fun `actual low at the left edge (start of observed data) is labeled`() {
        // Reproduces the live NWS day-view case: the actual (observed) line is flat overnight and its
        // coldest point sits at idx 0 (midnight, the start of the observed data). The forecast has its
        // own pre-dawn dip at idx 5 (so the forecast daily-low role does NOT claim idx 0). The actual
        // low at the left edge must still be labeled ACTUAL_LOW — it is a real boundary value, mirroring
        // the right-edge (NOW) exemption.
        val start = LocalDateTime.of(2026, 6, 16, 0, 0)
        // forecast: dips to 58 at idx 5, peaks 80 at idx 16 (min/max away from idx 0).
        val forecast = listOf(
            61f, 60f, 59.5f, 59f, 58.5f, 58f, 59f, 60f, 62f, 65f, 68f, 71f,
            74f, 76f, 78f, 79f, 80f, 79f, 77f, 74f, 70f, 67f, 64f, 63f,
        )
        // actual: coldest at idx 0 (61), warms to a 73 plateau midday, then eases back — min is the
        // left-edge point, max is interior, so neither the forecast HIGH nor LOW role lands on idx 0.
        val actual = listOf(
            61f, 61.2f, 61.4f, 61.6f, 62f, 62.5f, 63f, 64f, 66f, 68f, 70f, 72f,
            73f, 73f, 72f, 71f, 70f, 69f, 68f, 67f, 66f, 65f, 64f, 63f,
        )
        val hours = forecast.indices.map { i ->
            val dt = start.plusHours(i.toLong())
            HourData(
                dateTime = dt,
                temperature = forecast[i],
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actual[i],
            )
        }

        val lastIdx = hours.lastIndex
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, lastIdx, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = lastIdx,
            transitionX = null,
            observedAt = null,
            widthPx = 600,
        )

        assertTrue(
            "the left-edge actual low at idx 0 should be labeled ACTUAL_LOW",
            candidates.any { it.index == 0 && it.role == TemperatureRole.ACTUAL_LOW }
        )
    }
}
