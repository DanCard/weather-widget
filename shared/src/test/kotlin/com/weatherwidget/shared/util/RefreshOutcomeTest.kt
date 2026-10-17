package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.util.RefreshOutcome.Failed
import com.weatherwidget.shared.util.RefreshOutcome.HourlyIncomplete
import com.weatherwidget.shared.util.RefreshOutcome.Refreshed
import com.weatherwidget.shared.util.RefreshOutcome.Refused
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** plans/261016-history-refresh-outcome-shared-desktop-parity.md */
@Category(ShortDuration::class)
class RefreshOutcomeTest {
    private val start = 1_000_000L

    private fun outcome(
        ok: Boolean = true,
        hourly: Long? = null,
        daily: Long? = null,
        rateLimitAt: Long? = null,
    ) = RefreshOutcome.of(ok, hourly, daily, rateLimitAt, start)

    @Test
    fun `failed fetch - Failed even with blocks`() {
        assertEquals(Failed, outcome(ok = false, hourly = 5L))
    }

    @Test
    fun `nothing refused - Refreshed`() {
        assertEquals(Refreshed, outcome())
    }

    @Test
    fun `hourly quota-blocked - Refused hourly forecast`() {
        assertEquals(Refused(QuotaScope.HOURLY_FORECAST, 9L), outcome(hourly = 9L))
    }

    @Test
    fun `daily quota-blocked - Refused daily forecast`() {
        assertEquals(Refused(QuotaScope.DAILY_FORECAST, 9L), outcome(daily = 9L))
    }

    @Test
    fun `both blocked - Refused source with earliest reset`() {
        assertEquals(Refused(QuotaScope.SOURCE, 7L), outcome(hourly = 9L, daily = 7L))
    }

    @Test
    fun `partial rate limit during refresh - HourlyIncomplete`() {
        assertEquals(HourlyIncomplete, outcome(rateLimitAt = start + 1))
    }

    @Test
    fun `partial rate limit from before the tap - Refreshed`() {
        assertEquals(Refreshed, outcome(rateLimitAt = start - 1))
    }

    @Test
    fun `quota outranks a partial rate limit`() {
        assertEquals(Refused(QuotaScope.HOURLY_FORECAST, 9L), outcome(hourly = 9L, rateLimitAt = start + 1))
    }

    @Test
    fun `text - every outcome's English line`() {
        val time = { _: Long -> "12 AM" }
        assertEquals("Refreshed Google", RefreshOutcome.text(Refreshed, "Google", time))
        assertEquals("Couldn't refresh Google", RefreshOutcome.text(Failed, "Google", time))
        assertEquals(
            "Refreshed Google · Hourly forecast quota used · resets 12 AM",
            RefreshOutcome.text(Refused(QuotaScope.HOURLY_FORECAST, 0L), "Google", time),
        )
        assertEquals(
            "Refreshed Google · Daily forecast quota used · resets 12 AM",
            RefreshOutcome.text(Refused(QuotaScope.DAILY_FORECAST, 0L), "Google", time),
        )
        assertEquals(
            "Refreshed Google · Daily quota used · resets 12 AM",
            RefreshOutcome.text(Refused(QuotaScope.SOURCE, 0L), "Google", time),
        )
        assertEquals("Refreshed Google · Hourly forecast incomplete", RefreshOutcome.text(HourlyIncomplete, "Google", time))
    }

    @Test
    fun `logDetail - names the refused part`() {
        assertEquals(" refused=HOURLY_FORECAST until=9", RefreshOutcome.logDetail(outcome(hourly = 9L)))
        assertEquals(" refused=HOURLY_PARTIAL", RefreshOutcome.logDetail(HourlyIncomplete))
        assertEquals("", RefreshOutcome.logDetail(Refreshed))
        assertEquals("", RefreshOutcome.logDetail(Failed))
    }
}
