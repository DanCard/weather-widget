package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Reopening Forecast Comparison must show the main window's current source. The window stays
 * composed between showings, and the source was seeded once with an unkeyed `remember`. After the
 * main view switched to Open-Meteo, a reopen still showed NWS, which had one snapshot for a day
 * a week out, while Open-Meteo had many (user, 2026-11-19, Thu Nov 26).
 *
 * Real SQLite DAO + the window's content: the snapshot count comes from the stored rows. Since the
 * window shows the main source directly (DesktopDataScreenSourceSyncTest), a reopen and a live
 * switch both land on it.
 */
@Category(LongDuration::class)
class DesktopForecastComparisonReopenSourceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: Path
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.417
    private val lon = -122.089
    private val target = LocalDate.now().plusDays(7)

    private fun config(source: String) = DesktopConfig(
        lat = lat,
        lon = lon,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO"), weatherSource = source),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-comparison-reopen")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() })
        DesktopSourceViews.install(dao, executor = { it.run() })
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        // Open-Meteo: three fetches in different 4 h buckets, each with a changed high (new rows).
        listOf(20L to 65.8f, 12L to 61.4f, 2L to 60.4f).forEach { (hoursAgo, high) ->
            dao.upsertForecasts(lat, lon, WeatherSource.OPEN_METEO.id,
                listOf(DailyForecast(target.toString(), high, 48.7f, "Clear")), nowMs = now - hoursAgo * hour)
        }
        // NWS: the day just entered its 7-day horizon, one fetch.
        dao.upsertForecasts(lat, lon, WeatherSource.NWS.id,
            listOf(DailyForecast(target.toString(), 64f, 47f, "Clear")), nowMs = now - hour)
        println("seeded target=$target rows=${dao.getForecastEvolution(target.toEpochDay() * 86_400_000L, lat, lon).map { it.source to it.highTemp }}")
    }

    @After
    fun teardown() {
        DesktopSourceViews.install(null)
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `reopen after main source change shows the new source and its snapshots`() {
        var cfg by mutableStateOf(config("NWS"))
        var showRequestId by mutableIntStateOf(1)
        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = dao, config = cfg, showRequestId = showRequestId,
                initialDate = target, onClose = {},
            )
        }
        awaitLoaded()
        composeTestRule.onNodeWithTag("source_cycle_button").assertTextEquals(WeatherSource.NWS.shortDisplayName)
        composeTestRule.onNodeWithText("1 snapshot", substring = true).assertExists()

        // Main window switches to Open-Meteo, then the user reopens the window (DesktopUiApplication
        // bumps historyShowRequestId on every onOpenHistory).
        cfg = config("OPEN_METEO")
        showRequestId++
        awaitLoaded()

        composeTestRule.onNodeWithTag("source_cycle_button").assertTextEquals(WeatherSource.OPEN_METEO.shortDisplayName)
        composeTestRule.onNodeWithText("3 snapshots", substring = true).assertExists()
    }

    @Test
    fun `a config save that is not a source switch keeps the source`() {
        var cfg by mutableStateOf(config("OPEN_METEO"))
        composeTestRule.setContent {
            ForecastComparisonContent(weatherDao = dao, config = cfg, showRequestId = 1, initialDate = target, onClose = {})
        }
        awaitLoaded()
        // A config change that is not a reopen (e.g. a window-position save) must not reset the source.
        cfg = cfg.copy(historyWindowX = 10f)
        awaitLoaded()
        composeTestRule.onNodeWithTag("source_cycle_button").assertTextEquals(WeatherSource.OPEN_METEO.shortDisplayName)
    }

    private fun awaitLoaded() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitForIdle()
    }
}
