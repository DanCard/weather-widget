package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression cover for the Silurian history-actuals race
 * (`plans/260801-silurian-history-actuals-stale-source-race.md`).
 *
 * The worker filters the actuals map to the sources displayed when it loads, then repaints using
 * each widget's source read fresh at paint time. Toggling the API source mid-run made those two
 * sets disagree, and the past-day actual bars vanished until an unrelated later render restored
 * them. These cases pin the reconciliation between the two sets.
 */
@Category(ShortDuration::class)
class DailyActualsCoverageTest {

    @Test
    fun `no reload when the load already covers every painted source`() {
        assertTrue(
            DailyActualsCoverage.uncoveredSources(
                paintSourceIds = listOf("NWS", "SILURIAN"),
                loadedForSourceIds = listOf("SILURIAN", "NWS"),
            ).isEmpty(),
        )
    }

    @Test
    fun `source toggled in mid-run is reported uncovered`() {
        // The reported failure: widget 345 was OPEN_METEO when the worker started and SILURIAN by
        // the time it painted. Widgets 349/352 held NWS throughout.
        assertEquals(
            listOf("SILURIAN"),
            DailyActualsCoverage.uncoveredSources(
                paintSourceIds = listOf("SILURIAN", "NWS"),
                loadedForSourceIds = listOf("OPEN_METEO", "NWS"),
            ),
        )
    }

    @Test
    fun `repair reload keeps the sources that did not change`() {
        // NWS must survive the repair — widgets 349/352 still display it.
        assertEquals(
            listOf("OPEN_METEO", "NWS", "SILURIAN"),
            DailyActualsCoverage.unionSourceIds(
                paintSourceIds = listOf("SILURIAN", "NWS"),
                loadedForSourceIds = listOf("OPEN_METEO", "NWS"),
            ),
        )
    }

    @Test
    fun `coverage is measured against the filter set never against loaded keys`() {
        // A source that was requested but yielded no rows (no observations yet) must NOT be treated
        // as uncovered, or every run would pay for a pointless reload.
        assertTrue(
            DailyActualsCoverage.uncoveredSources(
                paintSourceIds = listOf("WEATHER_API"),
                loadedForSourceIds = listOf("WEATHER_API", "NWS"),
            ).isEmpty(),
        )
    }

    @Test
    fun `empty load treats every painted source as uncovered`() {
        assertEquals(
            listOf("SILURIAN"),
            DailyActualsCoverage.uncoveredSources(
                paintSourceIds = listOf("SILURIAN", "SILURIAN"),
                loadedForSourceIds = emptyList(),
            ),
        )
    }

    @Test
    fun `union deduplicates and is stable when nothing changed`() {
        assertEquals(
            listOf("NWS", "SILURIAN"),
            DailyActualsCoverage.unionSourceIds(
                paintSourceIds = listOf("NWS", "SILURIAN"),
                loadedForSourceIds = listOf("NWS", "SILURIAN"),
            ),
        )
    }
}
