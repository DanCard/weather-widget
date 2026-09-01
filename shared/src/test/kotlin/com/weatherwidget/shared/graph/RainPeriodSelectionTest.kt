package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

/**
 * Regression cover for the desktop "Actual rain in the future" bug (2026-09-01, Silurian).
 *
 * Desktop had its own copy of this logic. Its copy summed the *forecast* field for the actual
 * series and applied no now-gate, so the orange "Actual: " label printed the forecast number at
 * anchors that could sit entirely after NOW. Android was correct; this file locks the correct
 * behaviour in the one place both platforms now share.
 *
 * See plans/260901-share-rain-period-selection.md.
 */
@Category(ShortDuration::class)
class RainPeriodSelectionTest {

    private val midnight = LocalDateTime.of(2026, 9, 1, 0, 0)

    /** A full 24h window starting at midnight, so day (8a-8p) and night runs are both present. */
    private fun dayOfHours(
        forecastByHour: (Int) -> Float? = { null },
        actualByHour: (Int) -> Float? = { null },
    ) = (0 until 24).map { i ->
        RainPeriodSelection.RainHour(
            dateTime = midnight.plusHours(i.toLong()),
            precipAmountMm = forecastByHour(i),
            actualPrecipAmountMm = actualByHour(i),
            label = "${i}h",
        )
    }

    @Test
    fun `an actual period is never produced for hours that have not elapsed`() {
        // THE BUG. Rain forecast all day; NOW is 07:00, so only hours 0-6 carry an actual. The
        // desktop copy summed the forecast field and produced an actual label for the 8a-8p day run
        // -- entirely in the future.
        val hours = dayOfHours(
            forecastByHour = { 2f },
            // Caller-applied now-gate: null at and after 07:00.
            actualByHour = { i -> if (i < 7) 1f else null },
        )

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = 10f,
        )

        // The 8a-8p day run is entirely in the future, so it must carry no actual label at all --
        // that run is the one the desktop copy labelled "Actual: .003in" in the report. A segment
        // that merely CONTAINS the current hour is fine; the night run 0..7 is a legitimate region.
        assertTrue(
            "No actual period may be anchored in a fully-future region, got " +
                periods.actual.map { it.startIndex..it.endIndex },
            periods.actual.none { it.startIndex >= 7 },
        )
        assertEquals("Only the elapsed night run carries an actual", 1, periods.actual.size)
        assertEquals(
            "The actual total counts only the 7 elapsed hours, not the 24 forecast ones",
            7f,
            periods.actual.single().totalAmountMm,
        )
        assertTrue(
            "The forecast series must still label the future day run -- only the actual is withheld",
            periods.forecast.any { it.startIndex >= 8 },
        )
    }

    @Test
    fun `an actual period totals the actual field, not the forecast field`() {
        // THE BUG, second half: desktop passed { it.precipAmountMm } for both series, so the two
        // labels were always the same number. Forecast 5mm/h vs actual 1mm/h over 3 elapsed hours.
        val hours = dayOfHours(
            forecastByHour = { i -> if (i in 0..2) 5f else null },
            actualByHour = { i -> if (i in 0..2) 1f else null },
        )

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = 10f,
        )

        assertEquals("Forecast total is 3 x 5mm", 15f, periods.forecast.single().totalAmountMm)
        assertEquals("Actual total is 3 x 1mm, not the forecast's 15", 3f, periods.actual.single().totalAmountMm)
    }

    @Test
    fun `no actual data yields no actual periods at all`() {
        // Silurian exactly: historicalDataKind NONE, so the caller passes no observations. The old
        // desktop path still drew "Actual: " labels here, copied from the forecast.
        val hours = dayOfHours(forecastByHour = { 3f })

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = 10f,
        )

        assertTrue("Forecast labels are still expected", periods.forecast.isNotEmpty())
        assertTrue("A source with no actuals must produce no actual labels", periods.actual.isEmpty())
    }

    @Test
    fun `day and night runs split at 8a and 8p`() {
        val runs = RainPeriodSelection.dayNightRuns(dayOfHours(forecastByHour = { 1f }))

        assertEquals(3, runs.size)
        assertEquals(Triple(0, 7, false), Triple(runs[0].startIndex, runs[0].endIndex, runs[0].isDay))
        assertEquals(Triple(8, 19, true), Triple(runs[1].startIndex, runs[1].endIndex, runs[1].isDay))
        assertEquals(Triple(20, 23, false), Triple(runs[2].startIndex, runs[2].endIndex, runs[2].isDay))
    }

    @Test
    fun `boundary xs mark the 8a and 8p transitions`() {
        val xs = RainPeriodSelection.computeDayNightBoundaryXs(dayOfHours(forecastByHour = { 1f }), hourWidth = 10f)

        assertEquals(listOf(80f, 200f), xs)
    }

    @Test
    fun `the wettest day run and the wettest night run are both selected`() {
        // Rain at 9a-10a (day) and at 22:00 (night).
        val segments = RainPeriodSelection.selectDayNightSegments(
            dayOfHours(forecastByHour = { i -> if (i in 9..10) 3f else if (i == 22) 5f else 0f }),
        )

        assertEquals("At most one day + one night segment", 2, segments.size)
        assertTrue("Day segment covers the daytime run", segments.any { it.isDay && 9 in it.startIndex..it.endIndex })
        assertTrue("Night segment covers the late-night run", segments.any { !it.isDay && 22 in it.startIndex..it.endIndex })
    }

    @Test
    fun `segment ranking counts forecast plus actual`() {
        // A run that has already rained but whose forecast has been superseded must still rank, or
        // an elapsed downpour would lose its anchor to a drier forecast run.
        val hours = dayOfHours(
            forecastByHour = { i -> if (i in 21..22) 1f else null },
            actualByHour = { i -> if (i in 0..1) 9f else null },
        )

        val segments = RainPeriodSelection.selectDayNightSegments(hours)

        assertTrue(
            "The actual-only early-morning run must outrank the forecast-only late run",
            segments.any { !it.isDay && 0 in it.startIndex..it.endIndex },
        )
    }

    @Test
    fun `a dry window produces no periods in either series`() {
        val periods = RainPeriodSelection.selectPeriods(
            hours = dayOfHours(),
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = 10f,
        )

        assertTrue("No forecast rain, no forecast label", periods.forecast.isEmpty())
        assertTrue("No actual rain, no actual label", periods.actual.isEmpty())
    }

    @Test
    fun `per-hour mode labels only the first four hours where rain exists`() {
        // Index 0 dry, 1 forecast-only, 2 forecast+actual, 3 dry, 4 has rain but is past the cap.
        val hours = (0 until 5).map { i ->
            RainPeriodSelection.RainHour(
                dateTime = midnight.plusHours(i.toLong()),
                precipAmountMm = when (i) { 1 -> 2f; 2 -> 3f; 4 -> 9f; else -> 0f },
                actualPrecipAmountMm = if (i == 2) 1f else null,
                label = "${i}h",
            )
        }

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.PER_HOUR,
            hourWidth = 10f,
        )

        assertEquals("Forecast at hours 1 and 2 only (hour 4 is past the cap)", listOf(1, 2), periods.forecast.map { it.startIndex })
        assertEquals("Actual at hour 2 only", listOf(2), periods.actual.map { it.startIndex })
        assertTrue("Per-hour periods are single-hour", periods.forecast.all { it.startIndex == it.endIndex })
        assertEquals("Hour-1 anchored to its column x", 10f, periods.forecast.first().anchorX)
    }

    @Test
    fun `fixed window mode picks the wettest run of the given width`() {
        val amounts = listOf(0f, 1f, 4f, 5f, 1f, 0f)
        val hours = amounts.mapIndexed { i, amount ->
            RainPeriodSelection.RainHour(
                dateTime = midnight.plusHours(i.toLong()),
                precipAmountMm = amount,
                actualPrecipAmountMm = null,
                label = "${i}h",
            )
        }

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.WINDOW_TOTAL,
            hourWidth = 10f,
            windowHours = 3,
        )

        assertEquals(1, periods.forecast.size)
        assertEquals(10f, periods.forecast.first().totalAmountMm)
        assertTrue("No actuals supplied, so no actual window", periods.actual.isEmpty())
    }

    @Test
    fun `visible window mode totals the whole span`() {
        val hours = dayOfHours(
            forecastByHour = { 1f },
            actualByHour = { i -> if (i < 4) 0.5f else null },
        )

        val periods = RainPeriodSelection.selectPeriods(
            hours = hours,
            mode = RainPeriodSelection.Mode.WINDOW_TOTAL,
            hourWidth = 10f,
            windowHours = 0,
        )

        assertEquals("24 hours x 1mm", 24f, periods.forecast.single().totalAmountMm)
        assertEquals("4 elapsed hours x 0.5mm", 2f, periods.actual.single().totalAmountMm)
    }
}
