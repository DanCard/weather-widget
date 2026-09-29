package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
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
}
