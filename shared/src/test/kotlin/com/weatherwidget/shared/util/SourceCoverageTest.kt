package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceCoverageTest {
    private val mountainView = 37.4166 to -122.0889
    private val warsaw = 52.2334 to 21.0711

    @Test
    fun `inside coverage the enabled list is returned unchanged`() {
        val ids = listOf("NWS", "OPEN_METEO", "SILURIAN")
        assertSame(ids, SourceCoverage.effectiveSources(ids, mountainView.first, mountainView.second))
    }

    @Test
    fun `outside coverage NWS is filtered and the rest keep the user's order`() {
        assertEquals(
            listOf("OPEN_METEO", "SILURIAN"),
            SourceCoverage.effectiveSources(listOf("NWS", "OPEN_METEO", "SILURIAN"), warsaw.first, warsaw.second),
        )
    }

    @Test
    fun `an NWS-only list outside coverage falls back to Open-Meteo`() {
        assertEquals(listOf("OPEN_METEO"), SourceCoverage.effectiveSources(listOf("NWS"), warsaw.first, warsaw.second))
    }

    @Test
    fun `no location restricts nothing`() {
        val ids = listOf("NWS", "OPEN_METEO")
        assertSame(ids, SourceCoverage.effectiveSources(ids, null, null))
        assertSame(ids, SourceCoverage.effectiveSources(ids, Double.NaN, Double.NaN))
    }

    @Test
    fun `a user untick stays unticked inside coverage`() {
        // Nothing is ever added: coverage only subtracts.
        val ids = listOf("OPEN_METEO", "SILURIAN")
        assertSame(ids, SourceCoverage.effectiveSources(ids, mountainView.first, mountainView.second))
    }

    @Test
    fun `round trip out of and back into coverage needs no restore`() {
        val enabled = listOf("NWS", "OPEN_METEO")
        assertEquals(listOf("OPEN_METEO"), SourceCoverage.effectiveSources(enabled, warsaw.first, warsaw.second))
        assertEquals(enabled, SourceCoverage.effectiveSources(enabled, mountainView.first, mountainView.second))
    }

    @Test
    fun `supports is per source`() {
        assertFalse(SourceCoverage.supports("NWS", warsaw.first, warsaw.second))
        assertTrue(SourceCoverage.supports("OPEN_METEO", warsaw.first, warsaw.second))
        assertTrue(SourceCoverage.supports("NWS", mountainView.first, mountainView.second))
    }
}
