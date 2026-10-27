package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.data.remote.RateLimitNotice
import com.weatherwidget.data.remote.SourceErrorDetails
import com.weatherwidget.data.remote.SourceErrorDetails.Explanation
import com.weatherwidget.data.remote.SourceErrorDetails.Headline
import com.weatherwidget.data.remote.SourceErrorDetails.Label
import com.weatherwidget.data.remote.SourceErrorDetails.Value
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The popup's failure pill and its details card: the stored status row classified as Android records
 * it, and the card's content from the shared [SourceErrorDetails] over desktop's app_logs rows
 * (`plans/261027-error-and-quota-banner-click-shows-details-both-platforms.md`). Before, desktop worded
 * its own card and showed neither the HTTP status, the quota's metric and window, the failure count
 * nor the full response.
 */
@Category(ShortDuration::class)
class DesktopFetchErrorPresentationTest {
    private val tempDbPath = Files.createTempFile("weather-error-details-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val google = WeatherSource.GOOGLE_WEATHER.id

    private fun body(unit: String, limit: String) =
        """{"error":{"code":429,"message":"Quota exceeded.","details":[{"metadata":{"quota_unit":"$unit",""" +
            """"quota_limit":"ForecastHoursQueriesPerDay","quota_limit_value":"$limit",""" +
            """"quota_metric":"weather.googleapis.com/forecast/hours","window_start_time":"1791270000"}}]}}"""

    private fun failStatus(className: String, detail: String) =
        dao.log(CurrentTempStatusLog.TAG, "source=$google ok=false class=$className detail=$detail", "WARN")

    private fun details(viewed: ForecastProduct = ForecastProduct.HOURLY): SourceErrorDetails.Content? {
        val now = System.currentTimeMillis()
        val status = dao.getLatestCurrentTempStatus(google)?.takeIf { !it.ok }
        return desktopSourceErrorDetails(dao, google, status, viewed, now)
    }

    private fun SourceErrorDetails.Content.row(label: Label): Value? = rows.firstOrNull { it.label == label }?.value

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a daily quota 429 is a quota, its reset after the failure, with every provider detail`() {
        for (className in listOf("ApiAccessException", "GoogleDailyQuotaException")) {
            failStatus(className, "Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: ${body("1/d/{project}", "60")}")
            val status = dao.getLatestCurrentTempStatus(google)!!
            val kind = desktopFailureKind(status)
            assertTrue(className, kind.quota)
            assertFalse(className, kind.rateLimit)
            val c = details(ForecastProduct.DAILY)!!
            assertEquals(Headline.PAUSED, c.headline)
            assertEquals(Explanation.QUOTA, c.explanation)
            assertEquals(GoogleQuota.nextResetMs(status.timestamp), c.resetAtMs)
            assertEquals(Value.Plain("/forecast/hours:lookup"), c.row(Label.REQUEST))
            assertEquals(Value.Plain("429"), c.row(Label.STATUS))
            assertEquals(Value.Plain("weather.googleapis.com/forecast/hours"), c.row(Label.METRIC))
            assertEquals(Value.DateTime(1_791_270_000_000L), c.row(Label.WINDOW))
            assertEquals(
                "60 per day, shared by every device using this key",
                SourceErrorDetails.English.value(c.row(Label.LIMIT)!!, System.currentTimeMillis()),
            )
            assertTrue(c.rawResponse!!.contains("ForecastHoursQueriesPerDay"))
        }
    }

    @Test
    fun `a per-minute 429 stays a rate limit, shown with its limit`() {
        failStatus("ApiAccessException", "status 429. Detail: ${body("1/min/{project}/{user}", "20")}")
        val kind = desktopFailureKind(dao.getLatestCurrentTempStatus(google)!!)
        assertTrue(kind.rateLimit)
        assertFalse(kind.quota)
        val c = details()!!
        assertEquals(Explanation.RATE_LIMIT, c.explanation)
        assertEquals("20 per minute", SourceErrorDetails.English.value(c.row(Label.LIMIT)!!, System.currentTimeMillis()))
    }

    @Test
    fun `a rejected key offers the API key settings`() {
        failStatus("ApiAccessException", "Tomorrow.io realtime fetch failed: status 401.")
        val c = details()!!
        assertEquals(Explanation.AUTH, c.explanation)
        assertEquals(Value.ErrorCode("HTTP_401"), c.row(Label.ERROR))
        assertTrue(c.offerApiKeySettings)
    }

    @Test
    fun `a generic error keeps its whole message and counts the failures in a row`() {
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(google), "INFO")
        val detail = "A deliberately long provider error whose useful explanation appears after character forty"
        repeat(3) { failStatus("IllegalStateException", detail) }
        val c = details()!!
        assertEquals(Headline.FAILING, c.headline)
        assertEquals(Value.Plain(detail), c.row(Label.MESSAGE))
        assertEquals(Value.Plain("3"), c.row(Label.FAILURES))
    }

    @Test
    fun `a product block or a partial rate limit is described, not an older failure`() = runTest {
        val quotas = DesktopQuotaState(dao)
        quotas.recordPartialRateLimit(google, "status 429. Detail: ${body("1/min/{project}/{user}", "20")}")
        val partial = details()!!
        assertEquals(Headline.RATE_LIMITED, partial.headline)
        assertEquals(Value.ErrorCode(RateLimitNotice.ERROR_CODE_PARTIAL), partial.row(Label.ERROR))
        assertNull(partial.row(Label.FAILURES))

        quotas.recordProductQuota(google, ForecastProduct.DAILY, System.currentTimeMillis() + 3_600_000L, null)
        val block = details(ForecastProduct.DAILY)!!
        assertEquals(Headline.PAUSED, block.headline)
        assertEquals(QuotaScope.DAILY_FORECAST, (block.summary as SourceErrorDetails.Summary.Quota).scope)
    }

    @Test
    fun `nothing failing builds no details`() {
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(google), "INFO")
        assertNull(details())
        assertEquals(0, dao.getCurrentTempFailureStreak(google))
    }
}
