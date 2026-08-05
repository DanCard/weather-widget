package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TodayColumnOverlayPlannerTest {
    private val line = TodayColumnOverlayPlanner.Line("dominant", "62.6°\n15m", 50f, 20f)

    @Test
    fun `shared search uses above below and column bands without collisions`() {
        val above = TodayColumnOverlayPlanner.place(listOf(line), input()).single()
        val below = TodayColumnOverlayPlanner.place(
            listOf(line),
            input(listOf(bounds(0f, 0f, 100f, 40f))),
        ).single()
        val onColumn = TodayColumnOverlayPlanner.place(
            listOf(line),
            input(listOf(bounds(0f, 0f, 100f, 40f), bounds(0f, 80f, 100f, 120f))),
        ).single()

        assertEquals(TodayColumnOverlayPlanner.Zone.ABOVE, above.zone)
        assertEquals(TodayColumnOverlayPlanner.Zone.BELOW, below.zone)
        assertEquals(TodayColumnOverlayPlanner.Zone.ON_COLUMN, onColumn.zone)
        assertFalse(above.bounds.intersects(below.bounds))
        assertTrue(onColumn.bounds.top >= 40f)
    }

    private fun input(obstacles: List<TodayColumnOverlayPlanner.Bounds> = emptyList()) =
        TodayColumnOverlayPlanner.Input(0f, 100f, 0f, 120f, 40f, 80f, obstacles, 2f, 2f, 1f)

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        TodayColumnOverlayPlanner.Bounds(left, top, right, bottom)
}
