package com.weatherwidget.desktop

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * The data screens' source pill, wired through each window's content: left-click shows the next
 * source, right-click the previous one, both wrapping. [DesktopUiTest] covers [SourceCycleButton] with
 * hand-wired handlers; this guards each window passing its right-click handler (missing on History
 * of Forecasts and Stations when the header first got right-click, 2026-10-29).
 */
@Category(LongDuration::class)
class DesktopSourceCycleIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: Path
    private lateinit var dao: DesktopWeatherDao

    // Mountain View: inside NWS coverage, so all three sources are usable.
    private val config = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = "NWS"),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-source-cycle")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() })
        DesktopSourceViews.install(dao, executor = { it.run() })
    }

    @After
    fun teardown() {
        DesktopSourceViews.install(null)
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `history of forecasts source pill - left click next, right click previous`() {
        composeTestRule.setContent {
            ForecastHistoryContent(weatherDao = dao, config = config, onClose = {})
        }

        assertCyclesBothWays()
    }

    @Test
    fun `stations source pill - left click next, right click previous, each switch counted`() {
        composeTestRule.setContent {
            ObservationsContent(weatherDao = dao, config = config, onClose = {}, onConfigUpdate = {})
        }

        assertCyclesBothWays()

        val switches = dao.sourceViewDaysSince(0L)
            .filter { it.trigger == "OBSERVATIONS" }
            .groupBy { it.sourceId }
            .mapValues { (_, rows) -> rows.sumOf { it.switches } }
        assertEquals(mapOf("OPEN_METEO" to 1, "NWS" to 1, "SILURIAN" to 1), switches)
    }

    /** NWS → (left) Open-Meteo → (right) NWS → (right, wraps) Silurian, read from the pill's label. */
    private fun assertCyclesBothWays() {
        val pill = composeTestRule.onNodeWithTag("source_cycle_button")
        awaitLoaded()
        pill.assertLabel(WeatherSource.NWS)

        pill.performClick()
        awaitLoaded()
        pill.assertLabel(WeatherSource.OPEN_METEO)

        pill.performMouseInput { rightClick() }
        awaitLoaded()
        pill.assertLabel(WeatherSource.NWS)

        pill.performMouseInput { rightClick() }
        awaitLoaded()
        pill.assertLabel(WeatherSource.SILURIAN)
    }

    /**
     * Each switch reloads on Dispatchers.IO, which waitForIdle does not await. Click only once that
     * load lands, and let the last one finish before teardown deletes its database (it would fail
     * the next test).
     */
    private fun awaitLoaded() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertLabel(source: WeatherSource) =
        assertTextEquals(source.shortDisplayName)
}
