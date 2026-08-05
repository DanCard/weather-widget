package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WeightedColumnLayoutTest {
    @Test
    fun `Today gets one point two five units and tap lookup follows weighted edges`() {
        val layout = WeightedColumnLayout.resolve(825f, 8, todayColumnIndex = 1, widenToday = true)

        assertEquals(100f, layout.normalWidth, 0.001f)
        assertEquals(125f, layout.widths[1], 0.001f)
        assertEquals(1, layout.indexAt(100f))
        assertEquals(1, layout.indexAt(224.9f))
        assertEquals(2, layout.indexAt(225f))
        assertEquals(825f, layout.lefts.last() + layout.widths.last(), 0.001f)
        assertTrue(layout.centers.zipWithNext().all { (left, right) -> right > left })
    }
}
