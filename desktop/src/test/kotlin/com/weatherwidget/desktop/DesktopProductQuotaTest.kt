package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Google's forecast/hours quota spent while forecast/days still answers: the daemon records an
 * hourly-only block and every popup view names it, the view's own product first (user, 2026-10-21;
 * the daily view used to stay silent).
 */
@Category(ShortDuration::class)
class DesktopProductQuotaTest {
    private lateinit var dbFile: File
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        dbFile = File.createTempFile("product-quota", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbFile.toPath()).apply { initialize() })
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private val body = """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"1/d/{project}",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_limit_value":"90",""" +
        """"quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    @Test
    fun `the newest row per product is what the popup reads`() {
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked("GOOGLE_WEATHER", ForecastProduct.HOURLY, 1_791_442_800_000L, body))
        assertNull("daily product untouched", dao.getLatestProductQuota("GOOGLE_WEATHER", ForecastProduct.DAILY))

        val hourly = dao.getLatestProductQuota("GOOGLE_WEATHER", ForecastProduct.HOURLY)!!
        assertEquals(1_791_442_800_000L, ProductQuotaLog.parseUntilMs(hourly.second))
        assertEquals(body, ProductQuotaLog.parseDetail(hourly.second))

        Thread.sleep(2)
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.cleared("GOOGLE_WEATHER", ForecastProduct.HOURLY))
        assertEquals(0L, ProductQuotaLog.parseUntilMs(dao.getLatestProductQuota("GOOGLE_WEATHER", ForecastProduct.HOURLY)!!.second))
    }

    @Test
    fun `a product quota banner names that product and its reset, worded like the widget`() {
        for ((product, line) in listOf(
            ForecastProduct.HOURLY to "Hourly forecast quota used",
            ForecastProduct.DAILY to "Daily forecast quota used",
        )) {
            val c = com.weatherwidget.data.remote.SourceErrorDetails.build(
                com.weatherwidget.data.remote.SourceErrorDetails.Facts(
                    0, null, null, null,
                    { if (it == product) com.weatherwidget.data.remote.QuotaState.ProductQuota(1_791_442_800_000L, 0L, body) else null },
                    null, null,
                ),
                product,
                nowMs = 0L,
            )!!
            val english = com.weatherwidget.data.remote.SourceErrorDetails.English
            assertEquals("GOOGLE WEATHER UPDATES PAUSED", english.headline("Google Weather", c.headline))
            assertEquals("$line · resets ${QuotaNoticeText.formatResetTime(1_791_442_800_000L)}", english.summary(c.summary))
            val rows = c.rows.associate { english.label(it.label) to english.value(it.value, 0L) }
            assertEquals("ForecastHoursQueriesPerDay", rows["Quota"])
            assertEquals("90 per day, shared by every device using this key", rows["Limit"])
        }
    }

    @Test
    fun `an hourly-only block banners on the popup's daily view too`() {
        val untilMs = System.currentTimeMillis() + 3_600_000L
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked("GOOGLE_WEATHER", ForecastProduct.HOURLY, untilMs, body))
        val quotas = DesktopQuotaState(dao)
        val nowMs = System.currentTimeMillis()

        for (view in ViewMode.entries) {
            val (product, block) = requireNotNull(
                QuotaNotice.bannerBlock(QuotaNotice.productOf(view)) { quotas.productQuota("GOOGLE_WEATHER", it, nowMs) },
            ) { "$view view showed no banner" }
            assertEquals("$view", ForecastProduct.HOURLY, product)
            assertEquals("$view", untilMs, block.untilMs)
        }
    }

    @Test
    fun `an expired product quota block returns null on all views`() {
        val untilMs = 1_791_442_800_000L
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked("GOOGLE_WEATHER", ForecastProduct.HOURLY, untilMs, body))
        val quotas = DesktopQuotaState(dao)

        assertNull(quotas.productQuota("GOOGLE_WEATHER", ForecastProduct.HOURLY, nowMs = untilMs))
        assertNull(quotas.productQuota("GOOGLE_WEATHER", ForecastProduct.HOURLY, nowMs = untilMs + 1000L))

        for (view in ViewMode.entries) {
            val banner = QuotaNotice.bannerBlock(QuotaNotice.productOf(view)) {
                quotas.productQuota("GOOGLE_WEATHER", it, nowMs = untilMs + 1000L)
            }
            assertNull("$view view still showed banner after expiry", banner)
        }
    }
}
