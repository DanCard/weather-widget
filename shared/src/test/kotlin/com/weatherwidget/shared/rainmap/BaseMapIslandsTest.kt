package com.weatherwidget.shared.rainmap

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The packer's simplifier once dropped every closed ring — an island's coastline starts and ends on
 * one point, so its "segment" had no length — and the base map shipped without Great Britain,
 * Ireland, Japan or Hawaii (found 2026-10-21 drawing the radar around London;
 * `scripts/pack_natural_earth.py`).
 */
@Category(ShortDuration::class)
class BaseMapIslandsTest {
    private fun hasCoastIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, maxSpanDeg: Double): Boolean =
        BaseMap.linesIn(minLat, maxLat, minLon, maxLon).any {
            it.kind == BaseMap.Kind.COAST && it.maxLat - it.minLat < maxSpanDeg && it.maxLon - it.minLon < maxSpanDeg
        }

    @Test
    fun `islands are drawn - Great Britain, Ireland, Honshu, Oahu`() {
        assertTrue("Great Britain", hasCoastIn(50.0, 58.7, -6.0, 1.8, 12.0))
        assertTrue("Ireland", hasCoastIn(51.4, 55.4, -10.5, -6.0, 6.0))
        assertTrue("Honshu", hasCoastIn(33.4, 41.6, 129.8, 142.1, 14.0))
        assertTrue("Oahu", hasCoastIn(21.2, 21.8, -158.3, -157.6, 1.0))
    }
}
