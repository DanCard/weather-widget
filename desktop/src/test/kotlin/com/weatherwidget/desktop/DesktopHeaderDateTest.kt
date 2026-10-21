package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
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

    @Test
    fun `daily branch formats the date from now, not from the hourly target hour`() {
        val source = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin"))
            .first { it.isDirectory }
            .resolve("com/weatherwidget/desktop/DesktopWidgetHeader.kt")
            .readText()
        assertTrue(source.contains("DesktopHeaderDate.dailyDate(nowLocal).format(dateFormatter)"))
        assertTrue("daily date must not come from targetHour", !source.contains("val dateText = targetHour.format"))
    }
}
