package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId
import java.time.ZonedDateTime

@Category(ShortDuration::class)
class BugReportFormatTest {
    @Test
    fun timezoneLineNamesZoneAndOffset() {
        val phoenix = ZonedDateTime.of(2026, 11, 21, 21, 7, 0, 0, ZoneId.of("America/Phoenix"))
        assertEquals("Timezone: America/Phoenix (UTC-07:00)", BugReportFormat.timezoneLine(phoenix))
    }

    @Test
    fun timezoneLineSpellsOutUtcInsteadOfZ() {
        val utc = ZonedDateTime.of(2026, 11, 21, 4, 7, 0, 0, ZoneId.of("UTC"))
        assertEquals("Timezone: UTC (UTC+00:00)", BugReportFormat.timezoneLine(utc))
    }

    @Test
    fun timezoneLineFollowsDaylightSaving() {
        val warsawSummer = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw"))
        assertEquals("Timezone: Europe/Warsaw (UTC+02:00)", BugReportFormat.timezoneLine(warsawSummer))
    }
}
