package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.test.category.ShortDuration
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
 * hourly-only block and the popup's hourly view names it (user, 2026-10-07).
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
    fun `hourly quota banner names the hourly forecast, the reset and that daily still updates`() {
        val p = desktopHourlyQuotaPresentation("Google Weather", 1_791_442_800_000L, body)
        assertEquals("GOOGLE WEATHER HOURLY FORECAST QUOTA USED", p.title)
        assertTrue(p.bodyLines.any { it.startsWith("HTTP 429 — resets at ") })
        assertTrue(p.bodyLines.any { it.contains("daily forecast is still updating") })
    }
}
