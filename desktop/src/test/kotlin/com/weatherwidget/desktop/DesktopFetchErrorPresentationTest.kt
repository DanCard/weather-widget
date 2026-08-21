package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopFetchErrorPresentationTest {

    @Test
    fun `429 explains the request limit cached data and source isolation`() {
        val result = desktopFetchErrorPresentation(
            sourceDisplayName = "Tomorrow.io",
            className = "ApiAccessException",
            detail = "Tomorrow.io hourly fetch failed: status 429. Detail: Too Many Calls",
        )

        assertEquals("TOMORROW.IO REQUEST LIMIT REACHED", result.title)
        assertTrue(result.bodyLines.any { it == "HTTP 429 — Too Many Requests" })
        assertTrue(result.bodyLines.any { it.contains("Cached Tomorrow.io weather") })
        assertTrue(result.bodyLines.any { it.contains("No other weather provider") })
        assertEquals("The next scheduled refresh will try again.", result.retryLine)
    }

    @Test
    fun `401 identifies rejected credentials`() {
        val result = desktopFetchErrorPresentation(
            sourceDisplayName = "Tomorrow.io",
            className = "ApiAccessException",
            detail = "Tomorrow.io realtime fetch failed: status 401.",
        )

        assertEquals("TOMORROW.IO AUTHORIZATION FAILED", result.title)
        assertTrue(result.bodyLines.any { it == "HTTP 401 — Unauthorized" })
        assertTrue(result.retryLine.contains("API key"))
    }

    @Test
    fun `generic errors retain their complete detail instead of forty character truncation`() {
        val detail = "A deliberately long provider error whose useful explanation appears after character forty"
        val result = desktopFetchErrorPresentation(
            sourceDisplayName = "Silurian",
            className = "IllegalStateException",
            detail = detail,
        )

        assertEquals("SILURIAN WEATHER UPDATE FAILED", result.title)
        assertEquals(detail, result.bodyLines.first())
        assertFalse(result.bodyLines.first().endsWith("charact"))
    }
}
