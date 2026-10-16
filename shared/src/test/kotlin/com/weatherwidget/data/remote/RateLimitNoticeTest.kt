package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** `plans/261016-quota-refusals-always-show-a-banner.md` */
@Category(ShortDuration::class)
class RateLimitNoticeTest {
    private fun body(unit: String) = """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"$unit"}}]}}"""

    private fun error(status: Int, detail: String) =
        ApiAccessException(WeatherSource.GOOGLE_WEATHER, status, detail, "status $status. Detail: $detail")

    @Test
    fun `a 429 that is not a daily quota is a rate limit`() {
        assertTrue(RateLimitNotice.isRateLimit(error(429, body("1/min/{project}/{user}"))))
        assertTrue("no body: still a 429", RateLimitNotice.isRateLimit(error(429, "Too Many Requests")))
        assertFalse("daily quota has its own notice", RateLimitNotice.isRateLimit(error(429, body("1/d/{project}"))))
        assertFalse(RateLimitNotice.isRateLimit(error(500, "oops")))
        assertFalse(RateLimitNotice.isRateLimit(GoogleDailyQuotaException(0L, body("1/d/{project}"))))
        assertFalse(RateLimitNotice.isRateLimit(java.io.IOException()))
    }

    @Test
    fun `the banner shows for one minute after the refusal`() {
        assertTrue(RateLimitNotice.isShowing(1_000L, 1_000L))
        assertTrue(RateLimitNotice.isShowing(1_000L, 60_999L))
        assertFalse(RateLimitNotice.isShowing(1_000L, 61_000L))
        assertFalse(RateLimitNotice.isShowing(null, 0L))
        assertEquals(30_000L, RateLimitNotice.remainingMs(1_000L, 31_000L))
        assertNull(RateLimitNotice.remainingMs(1_000L, 61_000L))
    }

    @Test
    fun `codes and English copy`() {
        assertEquals(RateLimitNotice.ERROR_CODE_PARTIAL, RateLimitNotice.errorCode(partial = true))
        assertTrue(RateLimitNotice.isErrorCode(RateLimitNotice.ERROR_CODE))
        assertFalse(RateLimitNotice.isErrorCode("HTTP_429"))
        assertEquals("GOOGLE WEATHER RATE LIMITED", RateLimitNoticeText.headline("Google Weather"))
        assertEquals("Hourly forecast incomplete", RateLimitNoticeText.short(partial = true))
    }
}
