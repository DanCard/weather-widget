package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.cos
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TemperatureLabelSuppressionTest {

    @Test
    fun `nearby actual low and high with the same displayed temperature keep one label`() {
        // Samsung SM-F936U1, 2026-07-24: the last observed low before midnight was 62.48984
        // and the first observed high after midnight was 62.511925. They are distinct extrema in
        // their respective calendar days, but both draw as pink "62.5" only 20 minutes apart in
        // the narrow three-hour view, so the pair is redundant on screen.
        val start = LocalDateTime.of(2026, 7, 23, 23, 0)
        val actualTemps = listOf(
            63.596783f, 63.3f, 63.0f, 62.8f, 62.7f, 62.6f, 62.55f, 62.534042f,
            62.48984f, 62.504375f, 62.50727f, 62.511f, 62.511925f, 62.50727f,
            62.5f, 62.45f, 62.43284f,
        )
        val observedHours = actualTemps.mapIndexed { offset, actualTemperature ->
            val dt = start.plusMinutes(offset * 5L)
            HourData(
                dateTime = dt,
                temperature = 61.0f,
                label = "${dt.hour}:${dt.minute}",
                isActual = true,
                actualTemperature = actualTemperature,
            )
        }
        // The captured widget has five-minute observed points through 00:25, then hourly forecast
        // points at 01:00 and 02:00. That three-hour window makes the 20-minute actual pair 63px
        // apart at the 567px render width, just inside the visual-pair budget.
        val hours = observedHours + listOf(1L, 2L).map { forecastHour ->
            val dt = LocalDateTime.of(2026, 7, 24, forecastHour.toInt(), 0)
            HourData(dt, 60.0f, "${dt.hour}:00", isActual = false, actualTemperature = 60.0f)
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(
            hours = hours,
            transitionX = 252f,
            effectiveActualEndIndex = observedHours.lastIndex,
            fetchTime = null,
            useCelsius = false,
        )
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = observedHours.lastIndex,
            transitionX = 252f,
            observedAt = null,
            widthPx = 567,
            useCelsius = false,
        )

        val actual62Point5 = candidates.filter {
            it.role in setOf(TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW) &&
                TemperatureLabelResolver.formatTemp(it.labelTemps[it.index], useCelsius = false) == "62.5"
        }
        assertEquals(
            "The endpoint global low supersedes the nearby 62.5 wiggle, got ${actual62Point5.map { it.role to it.index }}",
            0,
            actual62Point5.size,
        )
        assertTrue(
            "The actual graph's true left-edge high must be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any {
                it.index == 0 &&
                    it.role == TemperatureRole.ACTUAL_HIGH &&
                TemperatureLabelResolver.formatTemp(it.labelTemps[it.index], useCelsius = false) == "63.6"
            },
        )
        assertTrue(
            "The actual graph's true right-edge low must be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any {
                it.index == observedHours.lastIndex &&
                    it.role == TemperatureRole.ACTUAL_LOW &&
                    TemperatureLabelResolver.formatTemp(it.labelTemps[it.index], useCelsius = false) == "62.4"
            },
        )
    }

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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null, useCelsius = false
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null, useCelsius = false
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
        // ACTUAL_HIGH is at index 1 (75.0f) — an INTERIOR observed peak (idx 0/2 are cooler), since a
        //   bare observation-cutoff edge is no longer treated as an actual extreme.
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
                actualTemperature = when (offset) {
                    0 -> 73.0f
                    1 -> 75.0f   // interior observed peak -> ACTUAL_HIGH at idx 1
                    2 -> 73.0f
                    else -> 60.0f + offset
                }
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 2, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 2,
            transitionX = 250f,
            observedAt = null, useCelsius = false
        )

        // ACTUAL_HIGH (at index 1) should be accepted.
        // FORECAST_HIGH (at index 3) should be suppressed by ACTUAL_HIGH.
        assertTrue("ACTUAL_HIGH should be accepted at index 1", candidates.any { it.index == 1 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertFalse("FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH", candidates.any { it.index == 3 && it.role == TemperatureRole.FORECAST_HIGH })
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 450f, 5, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 5,
            transitionX = 450f,
            observedAt = null, useCelsius = false
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null, useCelsius = false
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null, useCelsius = false
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
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 19, null, useCelsius = false)

        // Full widget width (~567px over 20h => ~28px/hour): ACTUAL_LOW@17 is ~3h ≈ 85px away, so
        // the zoom-aware window (≈2) no longer treats END@20 as redundant.
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 19,
            transitionX = null,
            observedAt = null,
            widthPx = 567, useCelsius = false,
        )

        assertTrue(
            "END at index 20 should be labeled on a zoomed-in view",
            candidates.any { it.index == 20 && it.role == TemperatureRole.END },
        )
    }

    @Test
    fun `END is still suppressed as redundant when the view is compressed`() {
        val hours = samsungFlatCurveHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 19, null, useCelsius = false)

        // Same data, narrow width (~120px over 20h => ~6px/hour): now 3h ≈ 18px, so ACTUAL_LOW@17
        // and END@20 genuinely read as a redundant pair and END is decluttered. Proves the window is
        // zoom-aware in BOTH directions, not just "always show END".
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 19,
            transitionX = null,
            observedAt = null,
            widthPx = 120, useCelsius = false,
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 9, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 250f,
            observedAt = null, useCelsius = false,
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 100f, 1, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 1,
            transitionX = 100f,
            observedAt = null, useCelsius = false,
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 250f, 9, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 250f,
            observedAt = null, useCelsius = false,
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 248f, 14, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 14,
            transitionX = 248f,
            observedAt = null,
            widthPx = 567, useCelsius = false,
        )

        // The daily HIGH (88) is still labeled once.
        assertTrue(
            "daily HIGH at 88 should be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.HIGH && TemperatureLabelResolver.formatTemp(it.labelTemps[it.index], useCelsius = false) == "88" },
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
            ) && TemperatureLabelResolver.formatTemp(it.labelTemps[it.index], useCelsius = false) == "88"
        }
        assertEquals(
            "exactly one forecast label should read 88, got ${eightyEightLabels.map { it.role to it.index }}",
            1, eightyEightLabels.size,
        )
    }

    @Test
    fun `actual low on the right-edge index is labeled as the visible observed low`() {
        // The observed line keeps cooling into the evening, so the absolute actual low lands on the
        // last (right-edge / NOW) index. The visible actual curve's true low must be labeled there;
        // the forecast END value (70) is a different series and must not replace it.
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
        )

        val rightEdge = candidates.find { it.index == 23 }
        assertEquals(
            "right-edge label should be the visible ACTUAL_LOW, got ${candidates.map { it.role to it.index }}",
            TemperatureRole.ACTUAL_LOW, rightEdge?.role,
        )
        assertEquals(
            "right-edge actual low should carry the observed endpoint (59)",
            59.0f, rightEdge!!.labelTemps[rightEdge.index], 0.01f,
        )
    }

    @Test
    fun `actual low on the fetch-dot right edge remains the visible observed low`() {
        // Current-day variant: the right-edge index is NOW, so the fetch dot lands on it. The observed
        // low (59) sits on that edge. The NOW marker must not hide the visible actual curve's true low.
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
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 23, hours.last().dateTime, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 23,
            transitionX = null,
            observedAt = 1_000L, // non-null => the fetch-dot path is active
            widthPx = 600, useCelsius = false,
        )

        assertTrue(
            "right-edge fetch-dot index (NOW) must remain the ACTUAL_LOW. " +
                "got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 23 && it.role == TemperatureRole.ACTUAL_LOW },
        )
    }

    @Test
    fun `absolute actual low at the observation cutoff edge is labeled`() {
        // The observed line cools monotonically toward NOW, so the absolute observed low lands on the
        // observation cutoff (idx 57 = the right edge of the observed data). It is the visible actual
        // curve's true low and must be labeled even without an interior turning point. (The interior
        // observed peak at idx 20 is still labeled.)
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 560f, effEnd, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effEnd,
            transitionX = 560f,
            observedAt = null,
            widthPx = 567, useCelsius = false,
        )

        assertTrue(
            "the observation-cutoff edge low at idx 57 must be labeled ACTUAL_LOW; " +
                "got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 57 && it.role == TemperatureRole.ACTUAL_LOW },
        )
        assertTrue(
            "the interior observed peak at idx 20 must still be labeled ACTUAL_HIGH; " +
                "got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 20 && it.role == TemperatureRole.ACTUAL_HIGH },
        )
    }

    // Forecast crests just inside the RIGHT edge then eases down to the endpoint. The global daily
    // HIGH (78) lives back at idx 8 (in the observed region) so the crest's role is FORECAST_HIGH, not
    // HIGH. n=60 => edgeWindow=min(5,59/15)=3; the crest at idx 56 has edgeDist=3 (inside the OLD
    // endpoint window). [crest] is the forecast-region peak; [endValue] sets the END value at idx 59.
    private fun rightEdgeCrestHours(crest: Float, endValue: Float): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 13, 0, 0)
        val n = 60
        val effEnd = 40 // observed 0..40; forecast region 40..59
        return (0 until n).map { i ->
            val dt = start.plusHours(i.toLong())
            val f = when {
                i <= effEnd -> 78f - 0.5f * kotlin.math.abs(i - 8)                       // daily HIGH (78) at idx 8
                i < 56 -> 62f + (crest - 62f) * (i - effEnd) / (56 - effEnd).toFloat()    // ramp 62 -> crest
                i == 56 -> crest                                                          // crest near right edge
                else -> crest + (endValue - crest) * (i - 56) / (59 - 56).toFloat()       // ease crest -> END
            }
            HourData(
                dateTime = dt,
                temperature = f,
                label = "${dt.hour}h",
                isActual = i <= effEnd,
                actualTemperature = f - 13f,
            )
        }
    }

    @Test
    fun `right-edge forecast crest is kept when it differs from the endpoint`() {
        // Live emulator case: forecast crests at 68 (idx 56) then eases to a 66 END (idx 59). The crest
        // is a genuine FORECAST_HIGH and must be labeled — the old endpoint-declutter dropped it.
        val hours = rightEdgeCrestHours(crest = 68f, endValue = 66f)
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 400f, 40, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 40,
            transitionX = 400f,
            observedAt = null,
            widthPx = 567, useCelsius = false,
        )

        assertTrue(
            "the right-edge forecast crest (68) at idx 56 must be labeled, not endpoint-suppressed; got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 56 && it.role == TemperatureRole.FORECAST_HIGH },
        )
    }

    @Test
    fun `near-edge forecast extremum wins over a redundant endpoint`() {
        // Crest (67.8) and END (67.5) read as the same value and sit pixel-near: the declutter must
        // prioritize the EXTREMUM and drop the ENDPOINT, not the reverse.
        val hours = rightEdgeCrestHours(crest = 67.8f, endValue = 67.5f)
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 400f, 40, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 40,
            transitionX = 400f,
            observedAt = null,
            widthPx = 567, useCelsius = false,
        )

        assertTrue(
            "the forecast crest at idx 56 must survive; got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 56 && it.role == TemperatureRole.FORECAST_HIGH },
        )
        assertFalse(
            "the redundant END at idx 59 must be the one suppressed; got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 59 && it.role == TemperatureRole.END },
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 350f, 9, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 9,
            transitionX = 350f,
            observedAt = null, useCelsius = false
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
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
        )

        // Regression: previously only the single globally-coldest valley got an ACTUAL_LOW; the
        // second day's actual low (idx 28) went unlabeled.
        assertTrue("day 0 actual low should be labeled at idx 4", candidates.any { it.index == 4 && it.role == TemperatureRole.ACTUAL_LOW })
        assertTrue("day 1 actual low should be labeled at idx 28", candidates.any { it.index == 28 && it.role == TemperatureRole.ACTUAL_LOW })
    }

    @Test
    fun `each day in a multi-day actual region gets its own actual high label`() {
        val hours = twoDayObservedHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
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

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 47, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 47,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
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
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 71, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 71,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
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
    fun `actual low at the left edge (start of observed data) is not labeled`() {
        // The observed line's coldest point sits at idx 0 (the start of the observed data / left edge)
        // and only warms from there — there is no interior valley. That edge sample is NOT a confirmed
        // low (the real overnight low may lie off-screen before the window), so it must NOT be labeled
        // ACTUAL_LOW. The interior midday peak (the 73 plateau) is still labeled ACTUAL_HIGH.
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
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, lastIdx, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = lastIdx,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
        )

        assertFalse(
            "the left-edge actual low at idx 0 must NOT be labeled ACTUAL_LOW (edge value); " +
                "got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 0 && it.role == TemperatureRole.ACTUAL_LOW }
        )
        assertTrue(
            "the interior midday peak (73 plateau, idx 12) must still be labeled ACTUAL_HIGH; " +
                "got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 12 && it.role == TemperatureRole.ACTUAL_HIGH }
        )
    }

    // Reproduces the live emulator 3-day case (2026-06-26): the left edge stacked THREE labels —
    // forecast HIGH (75), per-day ACTUAL_HIGH (72.7), and the forecast START boundary (73). The
    // observed region is densely sampled (sub-hourly), so the START at idx 0 is only a few PIXELS from
    // the per-day actual high yet ~20 indices away. The old index-window redundancy check (cap 8) could
    // not see they were pixel-adjacent, AND it only compared START against the GLOBAL actual high
    // (which here lands on the next day at idx 43), never the pixel-near per-day high — so START
    // survived as redundant noise. Builds a 31-sample one-minute cluster + an hourly remainder.
    private fun denseLeftEdgeHours(perDayActualHighPeak: Float): List<HourData> {
        val base = LocalDateTime.of(2026, 6, 24, 15, 0)
        val out = mutableListOf<HourData>()
        // Dense observed cluster: 31 samples one minute apart (15:00..15:30). Forecast peaks at 75
        // (idx 15 = daily HIGH); START at idx 0 reads ~71. The observed line peaks at
        // [perDayActualHighPeak] (idx 20 = a PER-DAY actual high) ~11px from START but 20 indices away.
        for (i in 0 until 31) {
            val dt = base.plusMinutes(i.toLong())
            val forecast = 75f - 0.27f * kotlin.math.abs(i - 15)            // 71 -> 75 -> 71
            val actual = perDayActualHighPeak - 0.10f * kotlin.math.abs(i - 20)
            out.add(HourData(dt, forecast, "${dt.minute}m", isActual = true, actualTemperature = actual))
        }
        // Sparse hourly remainder (16:00 d1 -> 08:00 d2): an overnight forecast low (57) and the GLOBAL
        // observed high (74) on the next day, so extrema.actualHighIndex is far from idx 0 and the
        // per-day list is what the START redundancy check must consult.
        val sparseForecast = listOf(71f, 69f, 66f, 63f, 60f, 58f, 57f, 58f, 61f, 64f, 67f, 70f, 72f, 73f, 72f, 71f, 70f)
        val sparseActual = listOf(71f, 70f, 68f, 66f, 64f, 62f, 61f, 62f, 65f, 68f, 71f, 73f, 74f, 73.5f, 72f, 71f, 70f)
        for (k in sparseForecast.indices) {
            val dt = base.plusHours((k + 1).toLong())
            out.add(HourData(dt, sparseForecast[k], "${dt.hour}h", isActual = true, actualTemperature = sparseActual[k]))
        }
        return out
    }

    @Test
    fun `left-edge START is dropped when a per-day actual high is pixel-near but index-far`() {
        val hours = denseLeftEdgeHours(perDayActualHighPeak = 72.7f) // START 71 vs 72.7 => 1.7° < 2°
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, hours.lastIndex, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = hours.lastIndex,
            transitionX = null,
            observedAt = null,
            widthPx = 584, useCelsius = false,
        )

        assertFalse(
            "redundant left-edge START at idx 0 should be suppressed, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 0 && it.role == TemperatureRole.START },
        )
        // The informative labels remain.
        assertTrue("forecast daily HIGH (75) should remain", candidates.any { it.role == TemperatureRole.HIGH })
    }

    @Test
    fun `left-edge START is retained when the nearby per-day actual high differs by more than 2 degrees`() {
        val hours = denseLeftEdgeHours(perDayActualHighPeak = 74f) // START 71 vs 74 => 3° > 2°
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, hours.lastIndex, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = hours.lastIndex,
            transitionX = null,
            observedAt = null,
            widthPx = 584, useCelsius = false,
        )

        assertTrue(
            "a genuinely distinct left-edge START should be kept, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 0 && it.role == TemperatureRole.START },
        )
    }

    // Reproduces the live emulator 24h view (2026-06-26): the forecast START at the left edge read 73,
    // ~2° below the pixel-near forecast HIGH of 75 on the SAME line, so the START label was redundant
    // noise. It is a forecast-vs-forecast boundary pair (the redundant neighbour is the forecast HIGH,
    // not an actual high), which the strict cross-series < 2f gate missed. The actual line sits 13°
    // below the forecast so only the forecast path is exercised. [highMinusStart] sets START's gap
    // below the HIGH.
    private fun twentyFourHourForecastStartHours(highMinusStart: Float): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 26, 13, 0)
        val f = FloatArray(25)
        f[0] = 75f - highMinusStart // START
        f[1] = 74.5f
        f[2] = 75f                  // forecast daily HIGH (idx 2, ~2h from the edge => pixel-near)
        for (i in 3..13) f[i] = 75f - (75f - 57f) * (i - 2) / (13 - 2) // decline to LOW 57 at idx 13
        for (i in 14..24) f[i] = 57f + (68f - 57f) * (i - 13) / (24 - 13) // rise to END 68 at idx 24
        return (0 until 25).map { i ->
            val dt = start.plusHours(i.toLong())
            HourData(
                dateTime = dt,
                temperature = f[i],
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = f[i] - 13f, // far below the forecast => actual path can't suppress START
            )
        }
    }

    @Test
    fun `left-edge forecast START is dropped when within 2 degrees of a pixel-near forecast HIGH`() {
        val hours = twentyFourHourForecastStartHours(highMinusStart = 2f) // START 73 vs HIGH 75
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, hours.lastIndex, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = hours.lastIndex,
            transitionX = null,
            observedAt = null,
            widthPx = 584, useCelsius = false,
        )

        assertFalse(
            "redundant forecast START at idx 0 should be suppressed, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 0 && it.role == TemperatureRole.START },
        )
        assertTrue("forecast HIGH (75) should remain at idx 2", candidates.any { it.index == 2 && it.role == TemperatureRole.HIGH })
    }

    @Test
    fun `left-edge forecast START is kept when more than 2 degrees from the forecast HIGH`() {
        val hours = twentyFourHourForecastStartHours(highMinusStart = 3f) // START 72 vs HIGH 75 => 3°
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, hours.lastIndex, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = hours.lastIndex,
            transitionX = null,
            observedAt = null,
            widthPx = 584, useCelsius = false,
        )

        assertTrue(
            "a forecast START a genuine 3° below the HIGH should be kept, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 0 && it.role == TemperatureRole.START },
        )
    }

    // Reproduces the live 3-day emulator bug (2026-06-26): on a multi-day COOLING trend, a day's
    // overnight low straddles midnight — its calendar-day minimum lands on a still-descending ~23:00
    // shoulder (fails isActualLocalMin), so that day yields NO per-day low. The two adjacent daily highs
    // then sit consecutive and the shoulder-drop collapses one of them. The diurnal trough between two
    // daily highs must be injected so BOTH highs survive AND the overnight low is labeled.
    // Cooling trend, hourly, Mon/Tue/Wed: each night colder than the previous, with the diurnal minimum
    // after midnight, so Tue's calendar min lands at Tue 23:00 (still descending into Wed's deeper pre-dawn
    // trough) and is rejected — leaving Tue with no per-day low.
    // Live geometry reproduced from a 2026-07-15 emulator render (identical on Pixel, Samsung and
    // desktop): a NARROW overnight window, 53 points 5 min apart, whose forecast falls 72 -> 67 by
    // idx 39 and then runs dead flat at 67 to the right edge. The daily LOW opens that plateau at
    // idx 39; END closes it at idx 52. Both read "67°".
    //
    // The LOW is DRAWN at the CENTER of its flat run (centerOfRun) — 58.75px from END at widthPx=470,
    // inside the 64px redundancy budget. Measuring at idx 39's own timestamp instead reports 117.5px
    // and keeps both, printing "67°" twice on the right edge. transitionX sits beyond widthPx because
    // the whole window is in the past (observations continue past the right edge).
    private fun flatForecastPlateauToRightEdgeHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 7, 14, 21, 0)
        val plateauStart = 39
        val controls = listOf(0 to 71.5f, 14 to 69.27f, 27 to 70.93f, 39 to 67.4f, 52 to 66.8f)
        fun actualAt(i: Int): Float {
            val hi = controls.indexOfFirst { it.first >= i }.coerceAtLeast(1)
            val (x0, y0) = controls[hi - 1]
            val (x1, y1) = controls[hi]
            return if (x1 == x0) y1 else y0 + (y1 - y0) * (i - x0) / (x1 - x0)
        }
        return (0 until 53).map { i ->
            val dt = start.plusMinutes(i * 5L)
            val forecast = if (i >= plateauStart) 67f else 72f - 5f * (i / plateauStart.toFloat())
            HourData(
                dateTime = dt,
                temperature = forecast,
                label = "${dt.hour}h",
                isActual = true,
                actualTemperature = actualAt(i),
            )
        }
    }

    @Test
    fun `END is suppressed when the daily LOW is drawn centered in a flat run reaching the right edge`() {
        val hours = flatForecastPlateauToRightEdgeHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 1312f, 52, null, useCelsius = false)

        // Guard the fixture: the bug only arises when LOW opens a flat run ending at the last index.
        assertEquals("fixture: daily LOW should anchor the start of the 67 plateau", 39, extrema.dailyLowIndex)
        assertEquals("fixture: plateau must reach the right edge", 67f, extrema.labelTemps[hours.lastIndex], 0.01f)

        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 52,
            transitionX = 1312f,
            observedAt = null,
            widthPx = 470, useCelsius = false,
        )

        assertTrue("fixture: the daily LOW itself should still be labeled",
            candidates.any { it.index == 39 && it.role == TemperatureRole.LOW })
        assertFalse(
            "END must not repeat the plateau LOW's value at the right edge, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.END },
        )
    }

    // The same plateau at emulator-5556's geometry (widthPx=584, 54 points): the LOW's run-centered
    // anchor lands 73px from END — OUTSIDE the 64px budget, where the Pixel's 58.75px fell inside.
    // The pair is still one plateau labeled twice, so suppression must not depend on that distance.
    @Test
    fun `END on a flat run is suppressed even when the run-centered LOW is beyond the pixel budget`() {
        val hours = flatForecastPlateauToRightEdgeHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 1312f, 52, null, useCelsius = false)

        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 52,
            transitionX = 1312f,
            observedAt = null,
            widthPx = 584, useCelsius = false,
        )

        assertTrue("fixture: the daily LOW itself should still be labeled",
            candidates.any { it.index == 39 && it.role == TemperatureRole.LOW })
        assertFalse(
            "END must not repeat the plateau LOW's value regardless of pixel gap, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.role == TemperatureRole.END },
        )
    }

    private fun coolingTrendMidnightStraddleHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 8, 0, 0) // Monday 00:00 -> Mon/Tue/Wed
        val controls = listOf(0 to 64f, 3 to 62f, 14 to 75f, 27 to 61f, 38 to 74f, 47 to 59f, 51 to 56f, 62 to 73f, 71 to 60f)
        fun actualAt(h: Int): Float {
            val hi = controls.indexOfFirst { it.first >= h }.coerceAtLeast(1)
            val (x0, y0) = controls[hi - 1]
            val (x1, y1) = controls[hi]
            return if (x1 == x0) y1 else y0 + (y1 - y0) * (h - x0) / (x1 - x0)
        }
        return (0 until 72).map { h ->
            val dt = start.plusHours(h.toLong())
            val a = actualAt(h)
            HourData(dateTime = dt, temperature = a + 3f, label = "${dt.hour}h", isActual = true, actualTemperature = a)
        }
    }

    @Test
    fun `midnight-straddle overnight low is injected between two daily highs so neither high is dropped`() {
        val hours = coolingTrendMidnightStraddleHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, hours.lastIndex, null, useCelsius = false)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = hours.lastIndex,
            transitionX = null,
            observedAt = null,
            widthPx = 600, useCelsius = false,
        )

        // All three days keep their actual high. Without the inter-peak injection, Tue's straddling
        // overnight low is missed, Mon-high (h14) and Tue-high (h38) collapse via shoulder-drop, and a
        // high vanishes (the live "no actual high for Wed 24" symptom).
        assertTrue("Mon actual high (h14) should be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 14 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertTrue("Tue actual high (h38) should be labeled", candidates.any { it.index == 38 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertTrue("Wed actual high (h62) should be labeled", candidates.any { it.index == 62 && it.role == TemperatureRole.ACTUAL_HIGH })
        // The Mon->Tue overnight trough (h27, ~61°) — missed by per-calendar-day detection — is injected
        // and labeled (the live "no actual low for following low temp" symptom).
        assertTrue("injected inter-peak overnight low (h27) should be labeled, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.index == 27 && it.role == TemperatureRole.ACTUAL_LOW })
    }
}
