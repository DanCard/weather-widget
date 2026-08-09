package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression cover for the source-snapshot race that produced a transient "no cloud data" on the
 * Fold (2026-08-08): the worker scoped its hourly SQL to the sources displayed BEFORE its fetch,
 * the user toggled the API indicator during the fetch, and the repaint then filtered the loaded
 * rows down to zero for the newly-selected source.
 */
@Category(ShortDuration::class)
class HourlyForecastLoaderSourceScopeTest {

    private val nws = WeatherSource.NWS.id
    private val tomorrow = WeatherSource.TOMORROW_IO.id
    private val openMeteo = WeatherSource.OPEN_METEO.id
    private val generic = WeatherSource.GENERIC_GAP.id

    @Test
    fun `no reload needed when the display source did not change during the fetch`() {
        val missing = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = listOf(nws, generic),
            displaySourceIdsAtPaint = listOf(nws),
        )

        assertTrue("A steady source must not trigger a reload, got $missing", missing.isEmpty())
    }

    @Test
    fun `source toggled during the fetch is reported missing`() {
        // The observed failure: scope snapshotted while SILURIAN/NWS showed, repaint wants TOMORROW_IO.
        val missing = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = listOf(nws, generic),
            displaySourceIdsAtPaint = listOf(tomorrow),
        )

        assertEquals(listOf(tomorrow), missing)
    }

    @Test
    fun `only the newly displayed source is reported when other widgets are unchanged`() {
        // Multiple widgets, one toggled: reload is needed, but the diagnostic must name only the
        // source that actually went missing — that string is what HOURLY_SOURCE_SNAPSHOT_STALE logs.
        val missing = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = listOf(nws, openMeteo, generic),
            displaySourceIdsAtPaint = listOf(nws, tomorrow),
        )

        assertEquals(listOf(tomorrow), missing)
    }

    @Test
    fun `a source dropping off screen does not force a reload`() {
        // Narrowing is harmless: the loaded set is a superset of what the repaint needs, so
        // reloading would spend a query for nothing.
        val missing = HourlyForecastLoader.sourcesMissingFromLoad(
            loadedSourceIds = listOf(nws, openMeteo, generic),
            displaySourceIdsAtPaint = listOf(nws),
        )

        assertTrue("Fewer sources on screen must not trigger a reload, got $missing", missing.isEmpty())
    }
}
