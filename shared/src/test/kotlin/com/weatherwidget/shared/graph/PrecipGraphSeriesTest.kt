package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PrecipGraphSeriesTest {
    @Test
    fun `plateau edge keeps the raw value`() {
        // NWS 1a..5a on 2026-11-04: smoothing turned 3a's 25 into 18.75 ("19%").
        val values = PrecipGraphSeries.values(listOf(25, 25, 25, 0, 0))
        println("PrecipGraphSeries plateau → $values")
        assertEquals(listOf(25f, 25f, 25f, 0f, 0f), values)
    }

    @Test
    fun `missing hours read 0 and out of range values clamp`() {
        assertEquals(listOf(0f, 0f, 100f, 40f), PrecipGraphSeries.values(listOf(null, -5, 130, 40)))
    }
}
