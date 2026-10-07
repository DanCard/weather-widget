package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId
import java.util.Locale

/** The quota rules Android's widget/error page and desktop's popup banner both present from. */
@Category(ShortDuration::class)
class QuotaNoticeTest {
    private val pacific = ZoneId.of("America/Los_Angeles")

    /** 2026-10-07 23:30 PDT. */
    private val lateEvening = 1_791_441_000_000L

    /** 2026-10-08 00:00 PDT. */
    private val midnight = 1_791_442_800_000L

    private val dailyBody = """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"1/d/{project}",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_limit_value":"60"}}]}}"""

    @Test
    fun `every quota error code maps to its scope and nothing else does`() {
        assertEquals(QuotaScope.SOURCE, QuotaScope.ofErrorCode(GoogleQuota.ERROR_CODE_DAILY))
        assertEquals(QuotaScope.HOURLY_FORECAST, QuotaScope.ofErrorCode(GoogleQuota.ERROR_CODE_HOURLY_FORECAST))
        assertEquals(QuotaScope.DAILY_FORECAST, QuotaScope.ofErrorCode(GoogleQuota.ERROR_CODE_DAILY_FORECAST))
        assertEquals(GoogleQuota.QUOTA_CODES, QuotaScope.entries.map { it.errorCode }.toSet())
        assertNull(QuotaScope.ofErrorCode("HTTP_429"))
        assertNull(QuotaScope.ofErrorCode(null))
    }

    @Test
    fun `a source failure resets at the midnight after it, however late it is read`() {
        val notice = QuotaNotice.forSourceFailure(GoogleQuota.ERROR_CODE_DAILY, lateEvening, dailyBody)!!
        assertEquals(midnight, notice.resetAtMs)
        assertEquals(notice, QuotaNotice.forSourceFailure(429, lateEvening, dailyBody))
    }

    @Test
    fun `non-quota failures are not notices`() {
        assertNull(QuotaNotice.forSourceFailure("HTTP_429", lateEvening, dailyBody))
        assertNull(QuotaNotice.forSourceFailure(GoogleQuota.ERROR_CODE_DAILY, null, dailyBody))
        assertNull(QuotaNotice.forSourceFailure(429, lateEvening, dailyBody.replace("1/d/", "1/min/")))
        assertNull(QuotaNotice.forSourceFailure(500, lateEvening, dailyBody))
    }

    @Test
    fun `each view shows only the product it draws`() {
        assertEquals(ForecastProduct.DAILY, QuotaNotice.productOf(ViewMode.DAILY))
        for (view in listOf(ViewMode.TEMPERATURE, ViewMode.PRECIPITATION, ViewMode.CLOUD_COVER)) {
            assertEquals(view.name, ForecastProduct.HOURLY, QuotaNotice.productOf(view))
        }
        assertEquals(QuotaScope.DAILY_FORECAST, QuotaNotice.forProductBlock(ForecastProduct.DAILY, midnight, null).scope)
        assertEquals(QuotaScope.HOURLY_FORECAST, QuotaNotice.forProductBlock(ForecastProduct.HOURLY, midnight, null).scope)
    }

    @Test
    fun `english copy matches the widget`() {
        val reset = QuotaNoticeText.formatResetTime(midnight, Locale.US, pacific)
        assertEquals("12 AM", reset)
        assertEquals("12:30 AM", QuotaNoticeText.formatResetTime(midnight + 30 * 60_000L, Locale.US, pacific))
        assertEquals("GOOGLE WEATHER UPDATES PAUSED", QuotaNoticeText.headline("Google Weather", Locale.US))
        assertEquals("Daily quota used · resets 12 AM", QuotaNoticeText.summary(QuotaScope.SOURCE, reset))
        assertEquals("Hourly forecast quota used · resets 12 AM", QuotaNoticeText.summary(QuotaScope.HOURLY_FORECAST, reset))
        assertEquals("Daily forecast quota used · resets 12 AM", QuotaNoticeText.summary(QuotaScope.DAILY_FORECAST, reset))
        assertEquals(
            listOf("Quota: ForecastHoursQueriesPerDay — 60 per day, shared by every device using this key"),
            QuotaNoticeText.detailLines(QuotaNotice.forProductBlock(ForecastProduct.HOURLY, midnight, dailyBody).provider),
        )
        assertEquals(emptyList<String>(), QuotaNoticeText.detailLines(null))
    }
}
