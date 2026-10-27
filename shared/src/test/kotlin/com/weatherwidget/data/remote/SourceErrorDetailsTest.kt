package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.SourceErrorDetails.Explanation
import com.weatherwidget.data.remote.SourceErrorDetails.Headline
import com.weatherwidget.data.remote.SourceErrorDetails.Label
import com.weatherwidget.data.remote.SourceErrorDetails.Value
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId
import java.util.Locale

@Category(ShortDuration::class)
class SourceErrorDetailsTest {
    private val now = 1_791_300_000_000L

    // Google's daily 429 (the Fold's, 2026-10-06), as stored with the failure.
    private val dailyQuota = "Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: " +
        """{"error":{"code":429,"message":"Quota exceeded for quota metric 'Weather API - Forecast Hours Usage'.",""" +
        """"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",""" +
        """"metadata":{"window_start_time":"1791270000","quota_unit":"1/d/{project}","quota_limit_value":"60",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    private fun facts(
        failureCount: Int = 0,
        code: String? = null,
        failedAt: Long? = null,
        detail: String? = null,
        quotas: Map<ForecastProduct, QuotaState.ProductQuota> = emptyMap(),
        rateLimit: SourceErrorDetails.RateLimit? = null,
        lastSuccessMs: Long? = null,
    ) = SourceErrorDetails.Facts(failureCount, code, failedAt, detail, { quotas[it] }, rateLimit, lastSuccessMs)

    private fun SourceErrorDetails.Content.row(label: Label): Value? = rows.firstOrNull { it.label == label }?.value

    @Test
    fun `nothing failing builds nothing`() {
        assertNull(SourceErrorDetails.build(facts(), ForecastProduct.DAILY, now))
        // A rate limit older than its minute has already left the pill.
        val old = SourceErrorDetails.RateLimit(now - RateLimitNotice.SHOW_MS - 1, partial = false, detail = null)
        assertNull(SourceErrorDetails.build(facts(rateLimit = old), ForecastProduct.DAILY, now))
    }

    @Test
    fun `a daily quota failure names the quota, its limit, window and reset`() {
        val c = SourceErrorDetails.build(
            facts(failureCount = 3, code = GoogleQuota.ERROR_CODE_DAILY, failedAt = now - 60_000, detail = dailyQuota, lastSuccessMs = now - 3_600_000),
            ForecastProduct.DAILY,
            now,
        )!!
        assertEquals(Headline.PAUSED, c.headline)
        assertEquals(Explanation.QUOTA, c.explanation)
        assertEquals(Value.Plain("/forecast/hours:lookup"), c.row(Label.REQUEST))
        assertEquals(Value.Plain("429"), c.row(Label.STATUS))
        assertEquals(Value.Plain("ForecastHoursQueriesPerDay"), c.row(Label.QUOTA))
        assertEquals(Value.Limit("60", ProviderErrorDetails.QuotaPeriod.DAY), c.row(Label.LIMIT))
        assertEquals(Value.Plain("weather.googleapis.com/forecast/hours"), c.row(Label.METRIC))
        assertEquals(Value.DateTime(1_791_270_000_000L), c.row(Label.WINDOW))
        assertEquals(Value.ResetTime(c.resetAtMs!!), c.row(Label.RESETS))
        assertEquals(Value.Plain("3"), c.row(Label.FAILURES))
        assertEquals(Value.Moment(now - 3_600_000), c.row(Label.LAST_SUCCESS))
        assertTrue(c.rawResponse!!.contains("\"quota_limit_value\": \"60\""))
        assertFalse(c.offerApiKeySettings)
    }

    @Test
    fun `the viewed product's quota block is the one described, as on its pill`() {
        // Android's page took the first block in enum order (HOURLY) even under the daily view's pill.
        val quotas = mapOf(
            ForecastProduct.HOURLY to QuotaState.ProductQuota(now + 1_000, now - 5_000, null),
            ForecastProduct.DAILY to QuotaState.ProductQuota(now + 2_000, now - 4_000, null),
        )
        val daily = SourceErrorDetails.build(facts(quotas = quotas), ForecastProduct.DAILY, now)!!
        assertEquals(SourceErrorDetails.Summary.Quota(QuotaScope.DAILY_FORECAST, now + 2_000), daily.summary)
        val hourly = SourceErrorDetails.build(facts(quotas = quotas), ForecastProduct.HOURLY, now)!!
        assertEquals(SourceErrorDetails.Summary.Quota(QuotaScope.HOURLY_FORECAST, now + 1_000), hourly.summary)
        // Only the other product blocked: still shown (QuotaNotice.bannerBlock).
        val other = SourceErrorDetails.build(facts(quotas = quotas - ForecastProduct.DAILY), ForecastProduct.DAILY, now)!!
        assertEquals(SourceErrorDetails.Summary.Quota(QuotaScope.HOURLY_FORECAST, now + 1_000), other.summary)
    }

    @Test
    fun `a failure streak wins over a product block, a block over a rate limit`() {
        val quotas = mapOf(ForecastProduct.HOURLY to QuotaState.ProductQuota(now + 1_000, now - 5_000, null))
        val rate = SourceErrorDetails.RateLimit(now - 10_000, partial = true, detail = null)
        val streak = SourceErrorDetails.build(facts(failureCount = 3, code = "TIMEOUT", failedAt = now, quotas = quotas, rateLimit = rate), ForecastProduct.HOURLY, now)!!
        assertEquals(SourceErrorDetails.Summary.Code("TIMEOUT"), streak.summary)
        assertEquals(Explanation.GENERIC, streak.explanation)
        val block = SourceErrorDetails.build(facts(quotas = quotas, rateLimit = rate), ForecastProduct.HOURLY, now)!!
        assertEquals(Headline.PAUSED, block.headline)
        val limited = SourceErrorDetails.build(facts(rateLimit = rate), ForecastProduct.HOURLY, now)!!
        assertEquals(Headline.RATE_LIMITED, limited.headline)
        assertEquals(Explanation.RATE_LIMIT, limited.explanation)
        assertEquals(Value.ErrorCode(RateLimitNotice.ERROR_CODE_PARTIAL), limited.row(Label.ERROR))
        assertNull(limited.row(Label.FAILURES))
    }

    @Test
    fun `a rejected key offers settings, a network error has no response`() {
        val auth = SourceErrorDetails.build(facts(failureCount = 3, code = "HTTP_401", failedAt = now), null, now)!!
        assertEquals(Explanation.AUTH, auth.explanation)
        assertTrue(auth.offerApiKeySettings)
        val network = SourceErrorDetails.build(facts(failureCount = 2, code = "DNS_ERROR", failedAt = now), null, now)!!
        assertNull(network.rawResponse)
        assertFalse(network.offerApiKeySettings)
    }

    @Test
    fun `English rows read like Android's page`() {
        val c = SourceErrorDetails.build(
            facts(failureCount = 3, code = GoogleQuota.ERROR_CODE_DAILY, failedAt = now - 60_000, detail = dailyQuota),
            ForecastProduct.DAILY,
            now,
        )!!
        val zone = ZoneId.of("America/Los_Angeles")
        val rows = c.rows.associate { SourceErrorDetails.English.label(it.label) to SourceErrorDetails.English.value(it.value, now, Locale.US, zone) }
        assertEquals("60 per day, shared by every device using this key", rows["Limit"])
        assertEquals("Oct 6, 12:00 AM", rows["Quota window started"])
        assertEquals("GOOGLE WEATHER UPDATES PAUSED", SourceErrorDetails.English.headline("Google Weather", c.headline, Locale.US))
        assertEquals("Daily quota used · resets 12 AM", SourceErrorDetails.English.summary(c.summary, Locale.US, zone))
    }
}
