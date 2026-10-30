package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastDeltaLineTest {
    @Test
    fun `no delta is the bare forecast`() {
        assertEquals(0f, ForecastDeltaLine.effectiveDelta(null), 0f)
        assertEquals(0f, ForecastDeltaLine.effectiveDelta(Float.NaN), 0f)
        assertEquals(listOf(60f, 61f), ForecastDeltaLine.expectedTemps(listOf(60f, 61f), null))
    }

    @Test
    fun `expected temps shift the forecast by the delta`() {
        assertEquals(listOf(62f, 63.5f), ForecastDeltaLine.expectedTemps(listOf(60f, 61.5f), 2f))
    }

    @Test
    fun `the ghost needs a delta of at least a tenth either way`() {
        assertTrue(ForecastDeltaLine.drawsGhost(0.1f))
        assertTrue(ForecastDeltaLine.drawsGhost(-0.1f))
        assertFalse(ForecastDeltaLine.drawsGhost(0.09f))
        assertFalse(ForecastDeltaLine.drawsGhost(null))
        assertFalse(ForecastDeltaLine.drawsGhost(Float.NaN))
    }
}
