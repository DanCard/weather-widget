package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.local.desktop.RateLimitLog
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.util.RefreshOutcome
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * The desktop History Refresh outcome, read from the rows a refresh writes (real DB + DAO + the shared
 * [RefreshOutcome] rule). plans/261016-history-refresh-outcome-shared-desktop-parity.md
 */
@Category(ShortDuration::class)
class HistoryRefreshOutcomeTest {
    private lateinit var dbFile: File
    private lateinit var dao: DesktopWeatherDao
    private val source = "GOOGLE_WEATHER"

    @Before
    fun setUp() {
        dbFile = File.createTempFile("history-refresh-outcome", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbFile.toPath()).apply { initialize() })
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private val now get() = System.currentTimeMillis()

    @Test
    fun `no rows - Refreshed`() {
        assertEquals(RefreshOutcome.Refreshed, historyRefreshOutcome(dao, source, ok = true, startedAtMs = now))
    }

    @Test
    fun `thrown refresh - Failed`() {
        assertEquals(RefreshOutcome.Failed, historyRefreshOutcome(dao, source, ok = false, startedAtMs = now))
    }

    @Test
    fun `active hourly block - Refused hourly forecast until its reset`() {
        val until = now + 3_600_000L
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked(source, ForecastProduct.HOURLY, until, "{}"))

        assertEquals(
            RefreshOutcome.Refused(QuotaScope.HOURLY_FORECAST, until),
            historyRefreshOutcome(dao, source, ok = true, startedAtMs = now),
        )
    }

    @Test
    fun `cleared or expired block - Refreshed`() {
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked(source, ForecastProduct.DAILY, now - 1_000L, "{}"))
        Thread.sleep(2)
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked(source, ForecastProduct.HOURLY, now + 3_600_000L, "{}"))
        Thread.sleep(2)
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.cleared(source, ForecastProduct.HOURLY))

        assertEquals(RefreshOutcome.Refreshed, historyRefreshOutcome(dao, source, ok = true, startedAtMs = now))
    }

    @Test
    fun `another source's block is not this one's`() {
        dao.log(ProductQuotaLog.TAG, ProductQuotaLog.blocked("NWS", ForecastProduct.HOURLY, now + 3_600_000L, "{}"))

        assertEquals(RefreshOutcome.Refreshed, historyRefreshOutcome(dao, source, ok = true, startedAtMs = now))
    }

    @Test
    fun `partial rate limit counts only after the tap`() {
        dao.log(RateLimitLog.TAG, RateLimitLog.partial(source, "{}"))
        Thread.sleep(5)
        val tap = now
        assertEquals(RefreshOutcome.Refreshed, historyRefreshOutcome(dao, source, ok = true, startedAtMs = tap))

        Thread.sleep(2)
        dao.log(RateLimitLog.TAG, RateLimitLog.partial(source, "{}"))
        assertEquals(RefreshOutcome.HourlyIncomplete, historyRefreshOutcome(dao, source, ok = true, startedAtMs = tap))
    }
}
