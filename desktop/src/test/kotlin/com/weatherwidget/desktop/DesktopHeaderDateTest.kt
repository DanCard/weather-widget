package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

@Category(ShortDuration::class)
class DesktopHeaderDateTest {
    @Test
    fun `daily header date is today whatever the hourly pan`() {
        val now = LocalDateTime.of(2026, 10, 21, 9, 56)
        val hourlyPanned = now.plusHours(27) // the saved hourlyOffset that printed "Thu 22"
        assertEquals(LocalDate.of(2026, 10, 22), hourlyPanned.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 21), DesktopHeaderDate.dailyDate(now))
    }
}
