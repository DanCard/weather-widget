package com.weatherwidget.desktop

import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.QuotaNoticeText
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
            failureMs = FAILURE_MS,
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
            failureMs = FAILURE_MS,
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
            failureMs = FAILURE_MS,
        )

        assertEquals("SILURIAN WEATHER UPDATE FAILED", result.title)
        assertEquals(detail, result.bodyLines.first())
        assertFalse(result.bodyLines.first().endsWith("charact"))
    }

    private val dailyQuotaBody = """{"error":{"code":429,"details":[{"metadata":{"quota_unit": "1/d/{project}",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_limit_value":"60"}}]}}"""

    @Test
    fun `a daily quota 429 reads like the widget and resets after the failure, not after now`() {
        for (className in listOf("ApiAccessException", "GoogleDailyQuotaException")) {
            val result = desktopFetchErrorPresentation(
                sourceDisplayName = "Google Weather",
                className = className,
                detail = "Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: $dailyQuotaBody",
                failureMs = FAILURE_MS,
            )
            val reset = QuotaNoticeText.formatResetTime(GoogleQuota.nextResetMs(FAILURE_MS))
            assertTrue(className, result.quota)
            assertEquals(className, "GOOGLE WEATHER UPDATES PAUSED", result.title)
            assertEquals("Daily quota used · resets $reset", result.bodyLines.first())
            assertTrue(result.bodyLines.contains("Quota: ForecastHoursQueriesPerDay — 60 per day, shared by every device using this key"))
            assertTrue(result.bodyLines.contains("Request: /forecast/hours:lookup"))
            assertEquals(QuotaNoticeText.explanation(reset), result.retryLine)
        }
    }

    @Test
    fun `a per-minute 429 stays a request-limit banner`() {
        val result = desktopFetchErrorPresentation(
            sourceDisplayName = "Google Weather",
            className = "ApiAccessException",
            detail = "status 429. Detail: " + dailyQuotaBody.replace("1/d/", "1/min/"),
            failureMs = FAILURE_MS,
        )
        assertEquals("GOOGLE WEATHER REQUEST LIMIT REACHED", result.title)
        assertFalse(result.quota)
    }

    private companion object {
        /** 2026-10-07 23:30 PDT: the quota resets 30 minutes later, whatever the clock says now. */
        const val FAILURE_MS = 1_791_441_000_000L
    }
}
