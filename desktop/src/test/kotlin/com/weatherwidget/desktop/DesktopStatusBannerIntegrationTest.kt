package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.ProductQuotaLog
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.RateLimitNotice
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.ViewMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integrated tests for the desktop status banner lifecycle over real SQLite and coroutines
 * (`plans/261028-desktop-quota-banner-expiry.md`).
 *
 * Verifies that:
 * 1. An active product quota block displays the banner, schedules an automatic expiry job,
 *    and clears the banner at `untilMs` without user intervention.
 * 2. An active source-wide daily quota displays the banner and clears at midnight Pacific.
 * 3. A quota failure from yesterday whose reset timestamp has passed is immediately recognized
 *    as expired and never bannered.
 * 4. A rate limit displays for one minute and clears automatically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Category(MediumDuration::class)
class DesktopStatusBannerIntegrationTest {
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val sampleConfig = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        viewMode = ViewMode.TEMPERATURE,
        settings = DesktopSettings(
            weatherSource = google,
            visibleSources = listOf(google),
        ),
    )

    private val sampleBody = """{"error":{"code":429,"message":"Quota exceeded.","details":[{"metadata":{"quota_unit":"1/d/{project}",""" +
        """"quota_limit":"ForecastHoursQueriesPerDay","quota_limit_value":"90",""" +
        """"quota_metric":"weather.googleapis.com/forecast/hours"}}]}}"""

    private fun logAt(tag: String, message: String, level: String = "INFO", timestamp: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement("INSERT INTO app_logs (timestamp, level, tag, message) VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setLong(1, timestamp)
                stmt.setString(2, level)
                stmt.setString(3, tag)
                stmt.setString(4, message)
                stmt.executeUpdate()
            }
        }
    }

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("desktop-banner-int-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `product quota block banners immediately and clears automatically at untilMs`() = runTest {
        var virtualTime = System.currentTimeMillis()
        val untilMs = virtualTime + 5_000L

        logAt(
            ProductQuotaLog.TAG,
            ProductQuotaLog.blocked(google, ForecastProduct.HOURLY, untilMs, sampleBody),
            "WARN",
            timestamp = virtualTime,
        )

        var latestState = DesktopStatusBannerState()
        val bannerTransitions = mutableListOf<String>()

        val controller = DesktopStatusBannerController(
            scope = this,
            weatherDao = dao,
            config = { sampleConfig },
            onStateChanged = { latestState = it },
            onLogBannerState = { state, prev ->
                dao.log("CURR_TEMP_BANNER", "state=$state (was $prev)", "INFO")
                bannerTransitions.add("state=$state (was $prev)")
            },
            nowMs = { virtualTime },
        )

        controller.updateStatus()
        runCurrent()

        // 1. Quota is active before untilMs
        assertNotNull("Banner error text is present", latestState.error)
        assertEquals(GoogleQuota.ERROR_CODE_HOURLY_FORECAST, latestState.pill?.errorCode)
        assertEquals(listOf("state=error (was none)"), bannerTransitions)

        // 2. Advance time by 4,999 ms (still before expiry)
        virtualTime += 4_999L
        advanceTimeBy(4_999L)
        runCurrent()
        assertNotNull("Banner is still up right before expiry", latestState.error)

        // 3. Advance time by 2 ms (reaching untilMs + 1 ms)
        virtualTime += 2L
        advanceTimeBy(2L)
        runCurrent()

        // 4. Banner must be cleared automatically by the expiry timer
        assertNull("Banner error must be cleared at untilMs", latestState.error)
        assertNull("Failure pill must be cleared at untilMs", latestState.pill)
        assertEquals(
            listOf("state=error (was none)", "state=none (was error)"),
            bannerTransitions,
        )
        controller.cancel()
    }

    @Test
    fun `source-wide daily quota from yesterday is not bannered after midnight Pacific`() = runTest {
        val yesterdayEvening = System.currentTimeMillis() - 24 * 3_600_000L
        val resetAtMidnight = GoogleQuota.nextResetMs(yesterdayEvening)

        logAt(
            CurrentTempStatusLog.TAG,
            "source=$google ok=false class=ApiAccessException detail=status 429. Detail: $sampleBody",
            "WARN",
            timestamp = yesterdayEvening,
        )

        var latestState = DesktopStatusBannerState()
        val controller = DesktopStatusBannerController(
            scope = this,
            weatherDao = dao,
            config = { sampleConfig },
            onStateChanged = { latestState = it },
            nowMs = { resetAtMidnight + 60_000L }, // 1 minute after midnight
        )

        controller.updateStatus()
        runCurrent()

        assertNull("Expired daily quota from yesterday must not banner today", latestState.error)
        assertNull(latestState.pill)
        controller.cancel()
    }

    @Test
    fun `source-wide daily quota active today expires at midnight Pacific`() = runTest {
        var virtualTime = System.currentTimeMillis()
        val resetAtMidnight = GoogleQuota.nextResetMs(virtualTime)

        logAt(
            CurrentTempStatusLog.TAG,
            "source=$google ok=false class=ApiAccessException detail=status 429. Detail: $sampleBody",
            "WARN",
            timestamp = virtualTime,
        )

        var latestState = DesktopStatusBannerState()
        val controller = DesktopStatusBannerController(
            scope = this,
            weatherDao = dao,
            config = { sampleConfig },
            onStateChanged = { latestState = it },
            nowMs = { virtualTime },
        )

        controller.updateStatus()
        runCurrent()

        // Banner active in afternoon
        assertNotNull("Banner must show while quota is active", latestState.error)
        assertEquals(GoogleQuota.ERROR_CODE_DAILY, latestState.pill?.errorCode)

        // Advance past midnight Pacific
        val remainingToMidnight = resetAtMidnight - virtualTime
        virtualTime = resetAtMidnight + 1L
        advanceTimeBy(remainingToMidnight + 1L)
        runCurrent()

        assertNull("Banner must clear once midnight Pacific passes", latestState.error)
        assertNull(latestState.pill)
        controller.cancel()
    }

    @Test
    fun `rate limit banner appears and clears after RateLimitNotice SHOW_MS`() = runTest {
        var virtualTime = System.currentTimeMillis()
        logAt(
            com.weatherwidget.data.local.desktop.RateLimitLog.TAG,
            com.weatherwidget.data.local.desktop.RateLimitLog.partial(google, "status 429. Detail: $sampleBody"),
            "WARN",
            timestamp = virtualTime,
        )

        var latestState = DesktopStatusBannerState()
        val controller = DesktopStatusBannerController(
            scope = this,
            weatherDao = dao,
            config = { sampleConfig },
            onStateChanged = { latestState = it },
            nowMs = { virtualTime },
        )

        controller.updateStatus()
        runCurrent()

        assertNotNull(latestState.error)
        assertEquals(RateLimitNotice.ERROR_CODE_PARTIAL, latestState.pill?.errorCode)

        // Advance by SHOW_MS (60s)
        virtualTime += RateLimitNotice.SHOW_MS + 1L
        advanceTimeBy(RateLimitNotice.SHOW_MS + 1L)
        runCurrent()

        assertNull("Rate limit banner must clear after 60s", latestState.error)
        assertNull(latestState.pill)
        controller.cancel()
    }
}
