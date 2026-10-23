package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopLogEntity
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AppLogsFilterTest {
    private val success = DesktopLogEntity(1L, "INFO", "REFRESH", "Fetch success")
    private val failure = DesktopLogEntity(2L, "WARN", "REFRESH_FAIL", "Network error")
    private val logs = listOf(success, failure)

    @Test
    fun `a blank query keeps every row`() {
        assertEquals(logs, filterLogs(logs, "  "))
    }

    @Test
    fun `matches the message ignoring case`() {
        assertEquals(listOf(failure), filterLogs(logs, "ERROR"))
    }

    @Test
    fun `matches the tag and the level`() {
        assertEquals(listOf(failure), filterLogs(logs, "fail"))
        assertEquals(listOf(failure), filterLogs(logs, "warn"))
    }
}
