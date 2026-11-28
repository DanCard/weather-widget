package com.weatherwidget.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ForecastComparisonHeader
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * ←/→ in Forecast Comparison change the viewed day (user, 2026-11-27). Wires the window's real key
 * handler ([handleSourceWindowKeyEvent]) to the handler [ForecastComparisonContent] registers, over a
 * real SQLite DAO: each step reloads that day's stored snapshots.
 */
@Category(LongDuration::class)
class DesktopForecastComparisonArrowKeysTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: Path
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.417
    private val lon = -122.089
    private val target = LocalDate.now().plusDays(3)
    private val config = DesktopConfig(
        lat = lat,
        lon = lon,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS"), weatherSource = "NWS"),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-comparison-arrows")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() })
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        // target: one fetch; target+1: two fetches in different buckets, so the snapshot count tells the days apart.
        dao.upsertForecasts(lat, lon, WeatherSource.NWS.id,
            listOf(DailyForecast(target.toString(), 64f, 47f, "Clear")), nowMs = now - hour)
        listOf(12L to 70f, 2L to 72f).forEach { (hoursAgo, high) ->
            dao.upsertForecasts(lat, lon, WeatherSource.NWS.id,
                listOf(DailyForecast(target.plusDays(1).toString(), high, 50f, "Clear")), nowMs = now - hoursAgo * hour)
        }
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `right and left arrows step the viewed day`() {
        var handler: ((Boolean) -> Boolean)? = null
        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = dao, config = config, showRequestId = 1, initialDate = target, onClose = {},
                onRegisterArrowKeyHandler = { handler = it },
            )
        }
        awaitLoaded()
        composeTestRule.onNodeWithText(ForecastComparisonHeader.dateLabel(target)).assertExists()
        composeTestRule.onNodeWithText("1 snapshot", substring = true).assertExists()
        assertNotNull(handler)

        assertTrue(press(Key.DirectionRight, handler))
        awaitLoaded()
        println("after → label=${ForecastComparisonHeader.dateLabel(target.plusDays(1))}")
        composeTestRule.onNodeWithText(ForecastComparisonHeader.dateLabel(target.plusDays(1))).assertExists()
        composeTestRule.onNodeWithText("2 snapshots", substring = true).assertExists()

        assertTrue(press(Key.DirectionLeft, handler))
        awaitLoaded()
        composeTestRule.onNodeWithText(ForecastComparisonHeader.dateLabel(target)).assertExists()
        composeTestRule.onNodeWithText("1 snapshot", substring = true).assertExists()
        // Arrows never touch the source.
        assertEquals("NWS", config.settings.weatherSource)
    }

    private fun press(key: Key, handler: ((Boolean) -> Boolean)?): Boolean {
        var result = false
        composeTestRule.runOnIdle {
            result = handleSourceWindowKeyEvent(
                key = key,
                type = KeyEventType.KeyDown,
                config = config,
                onClose = {},
                onUpdateConfig = { error("arrow ←/→ must not switch the source") },
                sourceSwitchTrigger = SourceViewTrigger.COMPARISON,
                arrowKeyHandler = handler,
            )
        }
        return result
    }

    private fun awaitLoaded() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitForIdle()
    }
}
