package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Category(ShortDuration::class)
class HourlyTimelineGeometryTest {
    private data class Item(
        val time: LocalDateTime,
        val current: Boolean = false,
    )

    @Test
    fun `time interpolation uses aligned point and item prefix`() {
        val start = LocalDateTime.of(2026, 7, 29, 10, 0)
        val items = (0..3).map { Item(start.plusHours(it.toLong())) }
        val points = listOf(0f to 1f, 20f to 2f, 40f to 3f)

        assertEquals(
            30f,
            HourlyTimelineGeometry.computeXForTime(
                targetTime = start.plusHours(1).plusMinutes(30),
                items = items,
                points = points,
                hourWidth = 20f,
                dateTimeOf = Item::time,
            )!!,
            0.001f,
        )
        assertEquals(
            60f,
            HourlyTimelineGeometry.computeXForTime(
                targetTime = start.plusHours(3),
                items = items,
                points = points,
                hourWidth = 20f,
                dateTimeOf = Item::time,
            )!!,
            0.001f,
        )
    }

    @Test
    fun `now x requires a matching point`() {
        val start = LocalDateTime.of(2026, 7, 29, 10, 0)
        val items = listOf(Item(start), Item(start.plusHours(1), current = true))
        assertNull(
            HourlyTimelineGeometry.computeNowX(
                items = items,
                points = listOf(0f to 0f),
                currentTime = start.plusHours(1).plusMinutes(30),
                hourWidth = 20f,
                isCurrentHour = Item::current,
                dateTimeOf = Item::time,
            ),
        )
    }

    @Test
    fun `day label endpoints use supplied locale`() {
        val first = LocalDateTime.of(2026, 6, 15, 8, 0)
        val last = LocalDateTime.of(2026, 6, 16, 20, 0)
        val endpoints =
            HourlyTimelineGeometry.dayLabelEndpoints(
                firstDateTime = first,
                lastDateTime = last,
                currentTime = last,
                locale = Locale.US,
            )
        assertEquals(LocalDate.of(2026, 6, 16), endpoints.today)
        assertEquals(LocalDate.of(2026, 6, 15), endpoints.leftDate)
        assertEquals("Mon", endpoints.leftText)
        assertEquals("Tue", endpoints.rightText)
    }
}
