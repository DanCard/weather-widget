package com.weatherwidget.shared.daily

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyAxisRangeTest {

    @Test
    fun `axis spans exactly the drawn temperatures, no padding`() {
        // Desktop padded 4 % (at least 1°) at both ends, leaving the coldest bar short of the bottom.
        val axis = DailyAxisRange.of(listOf(44.5f, 69.9f, 52f))
        assertEquals(44.5f, axis.min, 0f)
        assertEquals(69.9f, axis.max, 0f)
        assertEquals(25.4f, axis.range, 0.001f)
    }

    @Test
    fun `implausible temperatures stay off the axis`() {
        val axis = DailyAxisRange.of(listOf(50f, -100f, 60f, 999f))
        assertEquals(50f, axis.min, 0f)
        assertEquals(60f, axis.max, 0f)
        assertEquals(2, axis.rejected)
        assertEquals(-100f, axis.firstRejected, 0f)
    }

    @Test
    fun `no temperatures read as 0 to 100, one temperature as a 1 degree range`() {
        assertEquals(DailyAxisRange(0f, 100f), DailyAxisRange.of(emptyList()))
        assertEquals(1f, DailyAxisRange.of(listOf(50f)).range, 0f)
    }
}
