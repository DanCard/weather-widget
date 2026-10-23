package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** [DayTap]: the one day-tap outcome and log lines, both platforms. */
@Category(ShortDuration::class)
class DayTapTest {
    @Test
    fun aFetchableDayFetchesEvenWhenSomeHoursAreShown() {
        assertEquals(DayTap.Outcome.Fetch(120), DayTap.outcome(onDemandHours = 120, hasHourly = true))
    }

    @Test
    fun aDayNoFetchCanHelpSaysSoOnlyWhenEmpty() {
        assertEquals(DayTap.Outcome.NoData, DayTap.outcome(onDemandHours = null, hasHourly = false))
        assertEquals(DayTap.Outcome.Open, DayTap.outcome(onDemandHours = null, hasHourly = true))
    }

    @Test
    fun theLinesKeepTheirFields() {
        assertEquals(
            "date=2026-10-28 targetView=PRECIPITATION offset=96 precipGate=40(daily) clickSource=graph_day zone=MAIN_COLUMN",
            DayTap.line("2026-10-28", "PRECIPITATION", 96, "40(daily)", "graph_day", "zone=MAIN_COLUMN"),
        )
        assertEquals(
            "phase=no_data date=2026-10-28 mode=TEMPERATURE -> \"No hourly\"",
            DayTap.noHourlyLine(DayTap.Outcome.NoData, "2026-10-28", "TEMPERATURE", false, "No hourly"),
        )
        assertNull(DayTap.noHourlyLine(DayTap.Outcome.Open, "2026-10-28", "TEMPERATURE", true, null))
    }
}
