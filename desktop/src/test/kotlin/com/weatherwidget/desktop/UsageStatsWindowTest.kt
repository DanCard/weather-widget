package com.weatherwidget.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.data.remote.ApiUsageSummary
import com.weatherwidget.data.remote.UsageCounts
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings → Data Usage → "Usage stats…" (plans/261008-settings-usage-stats-screen-api-calls-per-source.md):
 * requests logged through the DAO come back through [DesktopWeatherDao.apiUsageSince] and
 * [ApiUsageSummary] into the window.
 */
@Category(LongDuration::class)
class UsageStatsWindowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("usage-stats")
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    private val pacific = ZoneId.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-08T16:00:00Z")

    private fun loggedSummary(): List<com.weatherwidget.data.remote.SourceUsage> {
        val db = DesktopWeatherDatabase(tempDir.resolve("weather.db"))
        db.initialize()
        val dao = DesktopWeatherDao(db)
        fun log(at: Instant, host: String, path: String, status: Int = 200) {
            val key = ApiUsageClassifier.classify(host, path)!!
            dao.logApiCall(ApiUsageClassifier.usageDayMs(key.source, at, pacific), key.source, key.endpoint, status)
        }
        repeat(3) { log(now, "weather.googleapis.com", "/v1/forecast/hours:lookup") }
        log(now, "weather.googleapis.com", "/v1/forecast/days:lookup")
        log(Instant.parse("2026-09-20T16:00:00Z"), "weather.googleapis.com", "/v1/forecast/hours:lookup", 429)
        log(now, "api.weather.gov", "/points/37.4,-122.1")
        val since = LocalDate.of(2026, 10, 8).minusDays(90).toEpochDay() * 86_400_000L
        return ApiUsageSummary.summarize(dao.apiUsageSince(since), now, pacific)
    }

    @Test
    fun `logged requests come back per source and endpoint`() {
        val summary = loggedSummary()
        assertEquals(listOf("GOOGLE_WEATHER", "NWS"), summary.map { it.sourceId })
        val google = summary.first()
        assertEquals(UsageCounts(today = 4, thisMonth = 4, lastMonth = 1, last90Days = 5), google.counts)
        assertEquals(listOf("forecast/hours", "forecast/days"), google.endpoints.map { it.endpoint })
        assertEquals(1, google.quotaRefused)
    }

    @Test
    fun `window shows sources, endpoints and the Pacific note`() {
        val summary = loggedSummary()
        composeTestRule.setContent { UsageStatsContent(apiUsage = summary, network = null) }
        // Network data moved here from Settings (user, 2026-10-08).
        composeTestRule.onNodeWithText("Past 24 Hours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Google Weather").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("forecast/hours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Errors: 1 · Quota refusals (429): 1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("usage_source_NWS").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Google's days and months run on Pacific time, as in its Cloud Console.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `settings Data Usage card opens usage stats`() {
        var opened = 0
        composeTestRule.setContent {
            SettingsWindow(
                config = DesktopConfig(lat = 0.0, lon = 0.0, label = "Test Location"),
                onClose = {},
                onSave = {},
                onExit = {},
                onOpenUsageStats = { opened++ },
                autoSaveDelayMs = 600_000L,
            )
        }
        composeTestRule.onNodeWithTag("usage_stats_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, opened)
        // The numbers live in the Usage stats window, not in Settings.
        composeTestRule.onNodeWithText("Past 24 Hours").assertDoesNotExist()
    }
}
