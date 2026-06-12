package com.weatherwidget.widget

import com.weatherwidget.shared.graph.NiceAxisScale
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NiceAxisScaleTest {

    @Test
    fun `typical temperature range snaps to round numbers`() {
        val scale = NiceAxisScale.compute(72.3f, 78.7f)
        assertTrue("min should be <= 72.3", scale.niceMin <= 72.3f)
        assertTrue("max should be >= 78.7", scale.niceMax >= 78.7f)
        assertTrue("tick interval should be a round number", scale.tickInterval % 1f == 0f || scale.tickInterval % 5f == 0f || scale.tickInterval % 2f == 0f)
        for (tick in scale.ticks) {
            assertEquals("tick $tick should be multiple of ${scale.tickInterval}",
                0f, tick % scale.tickInterval, 0.01f)
        }
    }

    @Test
    fun `small range expands to minimum range`() {
        val scale = NiceAxisScale.compute(73f, 75f, minRange = 5f)
        assertTrue("range should be >= 5", scale.range >= 4.9f)
        assertTrue("min should be <= 73", scale.niceMin <= 73f)
        assertTrue("max should be >= 75", scale.niceMax >= 75f)
    }

    @Test
    fun `single value gets symmetric expansion`() {
        val scale = NiceAxisScale.compute(72f, 72f, minRange = 10f)
        assertTrue("range should be >= 10", scale.range >= 9.9f)
        assertTrue("min should be <= 72", scale.niceMin <= 72f)
        assertTrue("max should be >= 72", scale.niceMax >= 72f)
    }

    @Test
    fun `ticks are monotonically increasing`() {
        val scale = NiceAxisScale.compute(32f, 95f)
        for (i in 1 until scale.ticks.size) {
            assertTrue("tick[$i] should be > tick[${i - 1}]",
                scale.ticks[i] > scale.ticks[i - 1])
        }
    }

    @Test
    fun `ticks span from niceMin to niceMax`() {
        val scale = NiceAxisScale.compute(45f, 67f)
        assertEquals("first tick should be niceMin", scale.niceMin, scale.ticks.first(), 0.01f)
        assertEquals("last tick should be niceMax", scale.niceMax, scale.ticks.last(), 0.01f)
    }

    @Test
    fun `nice range contains raw range`() {
        val scale = NiceAxisScale.compute(41.2f, 63.8f)
        assertTrue("niceMin <= rawMin", scale.niceMin <= 41.2f)
        assertTrue("niceMax >= rawMax", scale.niceMax >= 63.8f)
    }

    @Test
    fun `wide range uses interval of 10`() {
        val scale = NiceAxisScale.compute(20f, 80f, targetTickCount = 5)
        assertTrue("interval should be 10 or 20", scale.tickInterval == 10f || scale.tickInterval == 20f)
    }

    @Test
    fun `negative range works correctly`() {
        val scale = NiceAxisScale.compute(-15f, -3f)
        assertTrue("min should be <= -15", scale.niceMin <= -15f)
        assertTrue("max should be >= -3", scale.niceMax >= -3f)
        for (tick in scale.ticks) {
            assertTrue("tick $tick should be multiple of interval",
                tick % scale.tickInterval < 0.01f || tick % scale.tickInterval > scale.tickInterval - 0.01f)
        }
    }

    @Test
    fun `symmetric scale is centered on zero`() {
        val scale = NiceAxisScale.computeSymmetric(4.2f)
        assertEquals("should be symmetric around zero", -scale.niceMax, scale.niceMin, 0.01f)
        assertTrue("should contain ±4.2", scale.niceMax >= 4.2f)
    }

    @Test
    fun `symmetric scale with zero value uses minimum range`() {
        val scale = NiceAxisScale.computeSymmetric(0f, minRange = 6f)
        assertTrue("range should be at least minRange", scale.range >= 5.9f)
        assertEquals("should be symmetric", -scale.niceMax, scale.niceMin, 0.01f)
    }

    @Test
    fun `valueToY maps min to bottom and max to top`() {
        val scale = NiceAxisScale.compute(0f, 100f, minRange = 1f)
        val graphTop = 10f
        val graphHeight = 200f

        val bottomY = scale.valueToY(scale.niceMin, graphTop, graphHeight)
        val topY = scale.valueToY(scale.niceMax, graphTop, graphHeight)

        assertEquals("min maps to bottom", graphTop + graphHeight, bottomY, 0.1f)
        assertEquals("max maps to top", graphTop, topY, 0.1f)
    }

    @Test
    fun `valueToY maps midrange to center`() {
        val scale = NiceAxisScale.compute(0f, 100f, minRange = 1f)
        val graphTop = 10f
        val graphHeight = 200f

        val midY = scale.valueToY(50f, graphTop, graphHeight)
        assertEquals("mid should map to center", graphTop + graphHeight / 2f, midY, 0.1f)
    }

    @Test
    fun `very small range still produces valid ticks`() {
        val scale = NiceAxisScale.compute(72.1f, 72.2f, minRange = 5f)
        assertTrue("should have at least 2 ticks", scale.ticks.size >= 2)
        assertTrue("range should be positive", scale.range > 0)
    }

    @Test
    fun `target tick count influences interval`() {
        val scale3 = NiceAxisScale.compute(0f, 100f, targetTickCount = 3)
        val scale8 = NiceAxisScale.compute(0f, 100f, targetTickCount = 8)
        assertTrue("fewer target ticks = larger interval", scale3.tickInterval >= scale8.tickInterval)
    }

    @Test
    fun `tick count roughly matches target`() {
        val scale = NiceAxisScale.compute(0f, 100f, targetTickCount = 5)
        assertTrue("tick count should be reasonable (got ${scale.ticks.size})",
            scale.ticks.size in 3..10)
    }
}
