package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class DateLabelMillisTest {

    @Test
    fun `dateLabelMillis picks local noon for each fully visible day`() {
        val zone = ZoneId.of("UTC")
        // Window spans all of Jun 10 and Jun 11 exactly.
        val start = LocalDateTime.of(2026, 6, 10, 0, 0)
        val end = LocalDateTime.of(2026, 6, 11, 23, 0)
        val millis = dateLabelMillis(start, end, zone)

        val expected = setOf(
            LocalDateTime.of(2026, 6, 10, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 11, 12, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals(expected, millis)
    }

    @Test
    fun `dateLabelMillis clamps partial first and last days to the window edges`() {
        val zone = ZoneId.of("UTC")
        // Centered ~now: starts Jun 10 15:00 (noon already past) and ends Jun 12 15:00.
        val start = LocalDateTime.of(2026, 6, 10, 15, 0)
        val end = LocalDateTime.of(2026, 6, 12, 15, 0)
        val millis = dateLabelMillis(start, end, zone)

        val expected = setOf(
            start.atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 11, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 12, 12, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals(expected, millis)
    }
}
