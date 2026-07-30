package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SeriesSmoothingTest {
    @Test
    fun `global extrema and endpoints stay exact across iterations`() {
        val values = listOf(10f, 80f, 20f, 60f, 5f)
        val result = SeriesSmoothing.smoothValuesPreservingGlobalExtrema(values, iterations = 3)
        assertEquals(10f, result[0], 0.0001f)
        assertEquals(80f, result[1], 0.0001f)
        assertEquals(5f, result[4], 0.0001f)
    }

    @Test
    fun `preserved anchors are reapplied after every pass`() {
        val result =
            SeriesSmoothing.smoothValuesPreservingGlobalExtrema(
                listOf(0f, 100f, 0f, 0f, 0f),
                iterations = 2,
            )
        assertEquals(37.5f, result[2], 0.0001f)
        assertNotEquals(25f, result[2], 0.0001f)
    }

    @Test
    fun `global minimum can smooth while max and endpoints remain exact`() {
        val result =
            SeriesSmoothing.smoothValuesPreservingExtrema(
                values = listOf(40f, 0f, 100f, 0f, 40f),
                iterations = 2,
                preserveGlobalMax = true,
                preserveGlobalMin = false,
                preserveStart = true,
                preserveEnd = true,
            )
        assertEquals(listOf(40f, 52.5f, 100f, 52.5f, 40f), result)
    }

    @Test
    fun `all local extrema and plateau midpoint remain exact`() {
        val values = listOf(20f, 80f, 80f, 30f, 60f, 10f)
        val result = SeriesSmoothing.smoothValuesPreservingAllExtrema(values, iterations = 3)
        assertEquals(80f, result[1], 0.0001f)
        assertEquals(30f, result[3], 0.0001f)
        assertEquals(60f, result[4], 0.0001f)
    }

    @Test
    fun `short inputs and non-positive iterations are unchanged`() {
        val short = listOf(1f, 2f)
        assertEquals(short, SeriesSmoothing.smoothValues(short, iterations = 3))
        val values = listOf(1f, 9f, 2f)
        assertEquals(values, SeriesSmoothing.smoothValues(values, iterations = 0))
        assertEquals(values, SeriesSmoothing.smoothValues(values, iterations = -1))
    }
}
