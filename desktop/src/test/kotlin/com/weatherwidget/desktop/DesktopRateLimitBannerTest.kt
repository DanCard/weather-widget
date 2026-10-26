package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.RateLimitLog
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate

/**
 * A per-minute 429 is bannered on every view for a minute, and a fetch whose later hourly pages it
 * refused is bannered as incomplete (user, 2026-10-16).
 * `plans/261016-quota-refusals-always-show-a-banner.md`
 */
@Category(ShortDuration::class)
class DesktopRateLimitBannerTest {
    private val tempDbPath = Files.createTempFile("weather-rate-limit-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val google = WeatherSource.GOOGLE_WEATHER.id

    private fun body(unit: String, limit: String) =
        """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"$unit",""" +
            """"quota_limit":"ForecastHoursQueriesPerUser","quota_limit_value":"$limit",""" +
            """"quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a per-minute 429 is a rate limit, shown with its limit`() {
        val p = desktopFetchErrorPresentation(
            "Google Weather", "ApiAccessException",
            "Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: ${body("1/min/{project}/{user}", "20")}",
            failureMs = 0L,
        )
        assertTrue(p.rateLimit)
        assertFalse(p.quota)
        assertEquals("GOOGLE WEATHER RATE LIMITED", p.title)
        assertTrue(p.bodyLines.toString(), p.bodyLines.any { it.contains("20 per minute") })
    }

    @Test
    fun `a daily-quota 429 stays a quota, not a rate limit`() {
        val p = desktopFetchErrorPresentation(
            "Google Weather", "ApiAccessException",
            "status 429. Detail: ${body("1/d/{project}", "150")}",
            failureMs = 0L,
        )
        assertTrue(p.quota)
        assertFalse(p.rateLimit)
    }

    @Test
    fun `the partial presentation says the hourly forecast is incomplete`() {
        val p = desktopPartialRateLimitPresentation("Google Weather", body("1/min/{project}/{user}", "20"))
        assertTrue(p.rateLimit)
        assertTrue(p.bodyLines.first().startsWith("Hourly forecast incomplete"))
    }

    @Test
    fun `a refresh whose later hour pages were refused logs the partial rate limit`() = runTest {
        val today = LocalDate.now()
        val hourMs = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        coEvery { service.fetchForecast() } returns RawFetch(
            daily = (0 until 10).map { DailyForecast(today.plusDays(it.toLong()).toString(), 70f, 50f, "Cloudy") },
            hourly = (0 until 48).map { HourlyForecast(hourMs + it * 3_600_000L, 60f, "Cloudy") },
            partialRateLimitDetail = "status 429. Detail: ${body("1/min/{project}/{user}", "20")}",
        )
        assertNull(dao.getLatestRateLimit(google))

        DesktopWeatherRepository(service, dao, 37.4220, -122.0841, google)
            .refresh(now = System.currentTimeMillis(), reason = "test", horizon = ForecastHorizonCadence.Horizon.LONG)

        val (_, message) = requireNotNull(dao.getLatestRateLimit(google))
        assertTrue(message.startsWith("source=$google partial=true"))
        assertNotNull(RateLimitLog.parseDetail(message).takeIf { it.contains("ForecastHoursQueriesPerUser") })
    }
}
