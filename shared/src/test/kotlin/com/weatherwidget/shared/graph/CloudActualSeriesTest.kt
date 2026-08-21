package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudActualSeriesTest {
    private val quarter = 15 * 60_000L
    private val start = 1_800_000_000_000L

    @Test
    fun `native quarter-hour points are retained through the inclusive current timestamp`() {
        val points = CloudActualSeries.points(
            values = mapOf(
                start - quarter to 90,
                start to 68,
                start + quarter to 56,
                start + 2 * quarter to 42,
            ),
            startMs = start,
            endMs = start + quarter,
        )

        assertEquals(listOf(start, start + quarter), points.map { it.timeMs })
        assertEquals(listOf(68, 56), points.map { it.cover })
    }

    @Test
    fun `quarter-hour series splits rather than bridging a missing half hour`() {
        val points = listOf(
            TimedCloudCover(start, 80),
            TimedCloudCover(start + quarter, 60),
            TimedCloudCover(start + 4 * quarter, 20),
            TimedCloudCover(start + 5 * quarter, 10),
        )

        assertEquals(listOf(2, 2), CloudActualSeries.segments(points).map { it.size })
    }
}
