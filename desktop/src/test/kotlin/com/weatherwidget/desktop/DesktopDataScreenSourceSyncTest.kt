package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Forecast Comparison and Stations share the main view's source (user, 2026-11-19: "synchronizing
 * API sources between 'forecast comparison' window and main window", Stations too). A pill cycle
 * switches the main source through [DesktopSourceSwitch] like the API button; a main-window switch
 * shows in an open window at once. plans/261119-sync-data-screen-sources-with-main.md
 *
 * Real SQLite DAO + each window's content + a config holder that applies the app's real save policy
 * ([resolveDesktopConfigSave]) under the labels `DesktopUiApplication` passes; the switch's deferred
 * phase (view count, app_logs) runs inline so its rows can be read back. A holder that accepted every
 * save hid the first cut's bug: the pill saved under the window's label, and the merge put the old
 * source back (user, 2026-11-19: "clicking on NWS api in the forecast comparison window did nothing").
 */
@Category(LongDuration::class)
class DesktopDataScreenSourceSyncTest {
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
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = source),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-data-screen-sync")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() })
        DesktopSourceViews.install(dao, executor = { it.run() })
        DesktopSourceSwitch.install(dao, CoroutineScope(Dispatchers.Unconfined))
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        listOf(20L to 65.8f, 12L to 61.4f, 2L to 60.4f).forEach { (hoursAgo, high) ->
            dao.upsertForecasts(lat, lon, WeatherSource.OPEN_METEO.id,
                listOf(DailyForecast(target.toString(), high, 48.7f, "Clear")), nowMs = now - hoursAgo * hour)
        }
        dao.upsertForecasts(lat, lon, WeatherSource.NWS.id,
            listOf(DailyForecast(target.toString(), 64f, 47f, "Clear")), nowMs = now - hour)
    }

    @After
    fun teardown() {
        DesktopSourceSwitch.install(null)
        DesktopSourceViews.install(null)
        tempDir.toFile().deleteRecursively()
    }

    private fun switchesBy(trigger: SourceViewTrigger): Map<String, Int> =
        dao.sourceViewDaysSince(0L)
            .filter { it.trigger == trigger.name }
            .groupBy { it.sourceId }
            .mapValues { (_, rows) -> rows.sumOf { it.switches } }
            .also { println("DesktopDataScreenSourceSync: $trigger switches=$it") }

    private fun pill() = composeTestRule.onNodeWithTag("source_cycle_button")

    /**
     * Plays `saveConfigAndNotify(new, label)`: the real merge policy, minus disk and daemon. The
     * windows get their saves from [DataWindowSaves.forForecastComparison] / [DataWindowSaves.forStations], the
     * same factories `DesktopUiApplication` calls, so the label each channel carries is the app's.
     */
    private class ConfigHolder(initial: DesktopConfig) {
        var cfg by mutableStateOf(initial)
        val saveConfigAndNotify: (DesktopConfig, String) -> Unit = { draft, label ->
            cfg = resolveDesktopConfigSave(cfg, draft, label).config
            println("DesktopDataScreenSourceSync: save label=$label source=${cfg.settings.weatherSource}")
        }
        val history = DataWindowSaves.forForecastComparison(saveConfigAndNotify)
        val observations = DataWindowSaves.forStations(saveConfigAndNotify)
    }

    @Test
    fun `comparison pill switches the main source, counted as COMPARISON, both directions`() {
        val holder = ConfigHolder(config("NWS"))
        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = dao, config = holder.cfg, initialDate = target, onClose = {},
                saves = holder.history,
            )
        }
        awaitLoaded()
        pill().assertTextEquals(WeatherSource.NWS.shortDisplayName)

        pill().performClick()
        awaitLoaded()
        assertEquals("the main source moved", "OPEN_METEO", holder.cfg.settings.weatherSource)
        pill().assertTextEquals(WeatherSource.OPEN_METEO.shortDisplayName)
        composeTestRule.onNodeWithText("3 snapshots", substring = true).assertExists()

        pill().performMouseInput { rightClick() }
        awaitLoaded()
        assertEquals("right-click goes back and also persists", "NWS", holder.cfg.settings.weatherSource)
        pill().assertTextEquals(WeatherSource.NWS.shortDisplayName)

        assertEquals(mapOf("OPEN_METEO" to 1, "NWS" to 1), switchesBy(SourceViewTrigger.COMPARISON))
        assertEquals("no switch is counted as the API button", emptyMap<String, Int>(), switchesBy(SourceViewTrigger.TOGGLE))
    }

    @Test
    fun `a main-window switch shows in an open comparison window without a reopen`() {
        var cfg by mutableStateOf(config("NWS"))
        composeTestRule.setContent {
            ForecastComparisonContent(weatherDao = dao, config = cfg, showRequestId = 1, initialDate = target, onClose = {})
        }
        awaitLoaded()
        composeTestRule.onNodeWithText("1 snapshot", substring = true).assertExists()

        cfg = resolveDesktopConfigSave(cfg, config("OPEN_METEO"), "popup").config // the popup's API button; no reopen
        awaitLoaded()

        pill().assertTextEquals(WeatherSource.OPEN_METEO.shortDisplayName)
        composeTestRule.onNodeWithText("3 snapshots", substring = true).assertExists()
    }

    @Test
    fun `stations pill switches the main source, counted as OBSERVATIONS`() {
        val holder = ConfigHolder(config("NWS"))
        composeTestRule.setContent {
            CurrentObservationsContent(
                weatherDao = dao, config = holder.cfg, onClose = {},
                saves = holder.observations,
            )
        }
        awaitLoaded()
        pill().assertTextEquals(WeatherSource.NWS.shortDisplayName)

        pill().performClick()
        awaitLoaded()
        assertEquals("OPEN_METEO", holder.cfg.settings.weatherSource)
        pill().assertTextEquals(WeatherSource.OPEN_METEO.shortDisplayName)

        pill().performMouseInput { rightClick() }
        awaitLoaded()
        assertEquals("NWS", holder.cfg.settings.weatherSource)

        assertEquals(mapOf("OPEN_METEO" to 1, "NWS" to 1), switchesBy(SourceViewTrigger.OBSERVATIONS))
    }

    @Test
    fun `a main-window switch shows in an open Current Observations window`() {
        val holder = ConfigHolder(config("NWS"))
        composeTestRule.setContent {
            CurrentObservationsContent(
                weatherDao = dao, config = holder.cfg, onClose = {},
                saves = holder.observations,
            )
        }
        awaitLoaded()
        pill().assertTextEquals(WeatherSource.NWS.shortDisplayName)

        holder.saveConfigAndNotify(holder.cfg.copy(settings = holder.cfg.settings.copy(weatherSource = "SILURIAN")), "popup")
        awaitLoaded()
        pill().assertTextEquals(WeatherSource.SILURIAN.shortDisplayName)
        assertEquals("following the main source is not a switch", emptyMap<String, Int>(), switchesBy(SourceViewTrigger.OBSERVATIONS))
    }

    @Test
    fun `only the pill's save may change the source, the window's other saves keep it`() {
        val persisted = config("OPEN_METEO")
        val staleDraft = config("NWS").copy(historyWindowX = 10f, obsWindowX = 20f) // a lagging snapshot
        for (label in listOf("history-window", "observations-window")) {
            assertEquals("$label must not put an old source back", "OPEN_METEO",
                resolveDesktopConfigSave(persisted, staleDraft, label).config.settings.weatherSource)
        }
        for (label in ConfigSaveSource.SOURCE_SWITCHES) {
            assertEquals("$label is a switch", "NWS", resolveDesktopConfigSave(persisted, staleDraft, label).config.settings.weatherSource)
        }
        assertEquals("the window's own fields still save", 10f,
            resolveDesktopConfigSave(persisted, staleDraft, "history-window").config.historyWindowX)

        // The factories' channels, as the app wires them: only sourceSwitch may move the source.
        for (factory in listOf(DataWindowSaves::forForecastComparison, DataWindowSaves::forStations)) {
            val holder = ConfigHolder(persisted)
            val saves = factory(holder.saveConfigAndNotify)
            saves.window(staleDraft)
            assertEquals("a window save keeps the source", "OPEN_METEO", holder.cfg.settings.weatherSource)
            saves.sourceSwitch(staleDraft)
            assertEquals("the pill's save switches it", "NWS", holder.cfg.settings.weatherSource)
        }
    }

    /** Loads run on Dispatchers.IO, which waitForIdle does not await. */
    private fun awaitLoaded() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitForIdle()
    }
}
