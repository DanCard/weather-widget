package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TodayColumnOverlayPlannerTest {
    private val line =
        TodayColumnOverlayPlanner.Line(
            key = "dominant_temp_age",
            text = "63.4°\n15m",
            width = 50f,
            height = 10f,
        )

    @Test
    fun `prefers clear space above the Today bars`() {
        val placement = TodayColumnOverlayPlanner.place(listOf(line), input()).single()

        assertEquals(TodayColumnOverlayPlanner.Zone.ABOVE, placement.zone)
        assertTrue(placement.bounds.bottom <= 40f)
    }

    @Test
    fun `uses below when the above band is occupied`() {
        val placement =
            TodayColumnOverlayPlanner.place(
                listOf(line),
                input(hardObstacles = listOf(bounds(0f, 0f, 100f, 40f))),
            ).single()

        assertEquals(TodayColumnOverlayPlanner.Zone.BELOW, placement.zone)
        assertTrue(placement.bounds.top >= 80f)
    }

    @Test
    fun `uses the column when above and below are occupied`() {
        val placement =
            TodayColumnOverlayPlanner.place(
                listOf(line),
                input(
                    hardObstacles =
                        listOf(
                            bounds(0f, 0f, 100f, 40f),
                            bounds(0f, 80f, 100f, 120f),
                        ),
                ),
            ).single()

        assertEquals(TodayColumnOverlayPlanner.Zone.ON_COLUMN, placement.zone)
        assertTrue(placement.bounds.top >= 40f)
        assertTrue(placement.bounds.bottom <= 80f)
    }

    @Test
    fun `places two lines without overlap`() {
        val placements =
            TodayColumnOverlayPlanner.place(
                listOf(
                    line.copy(key = "delta", text = "+3.2 from yesterday", width = 70f),
                    line,
                ),
                input(),
            )

        assertEquals(2, placements.size)
        assertFalse(placements[0].bounds.intersects(placements[1].bounds))
    }

    @Test
    fun `retains available line when another is too wide`() {
        val placements =
            TodayColumnOverlayPlanner.place(
                listOf(line.copy(key = "delta", width = 120f), line),
                input(),
            )

        assertEquals(listOf("dominant_temp_age"), placements.map { it.key })
    }

    private fun input(
        hardObstacles: List<TodayColumnOverlayPlanner.Bounds> = emptyList(),
    ) =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = 120f,
            barTop = 40f,
            barBottom = 80f,
            hardObstacles = hardObstacles,
            horizontalPadding = 2f,
            padding = 2f,
            verticalStep = 1f,
        )

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        TodayColumnOverlayPlanner.Bounds(left, top, right, bottom)
}
