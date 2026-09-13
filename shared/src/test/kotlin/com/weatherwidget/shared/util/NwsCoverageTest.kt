package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsCoverageTest {
    @Test
    fun `covers the US and its boxed territories`() {
        assertTrue(NwsCoverage.covers(37.4168, -122.0890)) // Mountain View
        assertTrue(NwsCoverage.covers(61.2181, -149.9003)) // Anchorage
        assertTrue(NwsCoverage.covers(21.3069, -157.8583)) // Honolulu
        assertTrue(NwsCoverage.covers(18.4655, -66.1057)) // San Juan
        assertFalse(NwsCoverage.covers(49.8419, 24.0316)) // Lviv
        assertFalse(NwsCoverage.covers(51.5074, -0.1278)) // London
    }

    @Test
    fun `retireNws drops NWS and keeps the rest in order`() {
        assertEquals(
            listOf("OPEN_METEO", "SILURIAN"),
            NwsCoverage.retireNws(listOf("NWS", "OPEN_METEO", "SILURIAN")),
        )
    }

    @Test
    fun `retireNws never empties the list`() {
        assertEquals(listOf("OPEN_METEO"), NwsCoverage.retireNws(listOf("NWS")))
    }

    @Test
    fun `retireNws is an identity no-op without NWS`() {
        val ids = listOf("OPEN_METEO", "SILURIAN")
        assertSame(ids, NwsCoverage.retireNws(ids))
    }

    @Test
    fun `restoreNws puts NWS back in front and is an identity no-op when present`() {
        assertEquals(listOf("NWS", "OPEN_METEO", "SILURIAN"), NwsCoverage.restoreNws(listOf("OPEN_METEO", "SILURIAN")))
        val ids = listOf("OPEN_METEO", "NWS")
        assertSame(ids, NwsCoverage.restoreNws(ids))
    }

    @Test
    fun `visibleSourcesFor only retires outside coverage`() {
        val ids = listOf("NWS", "OPEN_METEO")
        assertSame(ids, NwsCoverage.visibleSourcesFor(37.4168, -122.0890, ids))
        assertEquals(listOf("OPEN_METEO"), NwsCoverage.visibleSourcesFor(49.8419, 24.0316, ids))
    }
}
