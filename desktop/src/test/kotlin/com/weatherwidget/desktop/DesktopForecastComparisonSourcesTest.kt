package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Forecast Comparison' "All sources for <day>" card through [ForecastComparisonContent] and the real
 * SQLite reads: sorted by high with Android's short names (`ForecastComparisonSourcesRoboTest`).
 * plans/261105-all-sources-day-comparison.md
 */
@Category(LongDuration::class)
class DesktopForecastComparisonSourcesTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: Path
    private lateinit var db: DesktopWeatherDatabase
    private val lat = 37.42
    private val lon = -122.08
    private val config = DesktopConfig(
        lat = lat,
        lon = lon,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = "NWS"),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-source-compare")
        db = DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() }
        DesktopSourceViews.install(DesktopWeatherDao(db), executor = { it.run() })
    }

    @After
    fun teardown() {
        DesktopSourceViews.install(null)
        tempDir.toFile().deleteRecursively()
    }

    private fun seed(source: String, high: Float, low: Float, ageMs: Long, noonCloud: Int? = null, dayMax: Int? = null, dayOffset: Long = 1) {
        val today = LocalDate.now()
        val fetched = System.currentTimeMillis() - ageMs
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                    "isClimateNormal, source, lastConfirmedAt, fetchedAt, noonCloudPercent, hourlyDayPrecipMax) VALUES (?, ?, ?, ?, ?, ?, 'Cloudy', 0, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                listOf<Any?>(
                    today.plusDays(dayOffset).toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon, high, low,
                    source, fetched, fetched, noonCloud, dayMax,
                ).forEachIndexed { i, v -> stmt.setObject(i + 1, v) }
                stmt.executeUpdate()
            }
        }
    }

    @Test
    fun `all sources card lists tomorrow's forecasts sorted by high with short names`() {
        seed("NWS", 66f, 50f, ageMs = 3_600_000L)
        seed("OPEN_METEO", 68f, 52f, ageMs = 2 * 3_600_000L, noonCloud = 40, dayMax = 15)
        val tomorrow = LocalDate.now().plusDays(1)

        composeTestRule.setContent {
            ForecastComparisonContent(weatherDao = DesktopWeatherDao(db), config = config, initialDate = tomorrow, onClose = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }

        val title = "All sources for ${tomorrow.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} " +
            "${tomorrow.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${tomorrow.dayOfMonth}"
        composeTestRule.onNodeWithText(title).assertExists()
        fun rowTexts(i: Int): List<String> =
            composeTestRule.onNodeWithTag("source_compare_row_$i", useUnmergedTree = true)
                .fetchSemanticsNode().children.map { it.config[SemanticsProperties.Text].joinToString() }
        val rows = (0..2).map { rowTexts(it) }
        println("DesktopForecastComparisonSourcesTest rows: $rows")
        assertEquals(listOf("Meteo", "NWS", "Silur"), rows.map { it.first() })
        assertEquals(listOf("Meteo", "68°", "52°", "15%", "—", "—", "40%", "—", "2h"), rows[0])
        assertEquals("1h", rows[1].last())
        assertEquals(List(8) { "—" }, rows[2].drop(1))
        assertTrue(composeTestRule.onAllNodesWithText("68°").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("40%").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("2h").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("1h").fetchSemanticsNodes().isNotEmpty())
        val legend = composeTestRule.onNodeWithTag("source_compare_legend", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        println("DesktopForecastComparisonSourcesTest legend: $legend")
        assertTrue(legend, legend.startsWith("Day highest hourly chance, 8am–8pm\nPeak hour of the highest daytime chance\n"))
        assertTrue(legend, legend.contains("\nCloud cloud cover at noon\n"))
        assertTrue(legend, legend.endsWith("Age time since the source last confirmed this forecast; grey after 24 h\n$APPLY_ORDER_LABEL $APPLY_ORDER_NOTE"))
        assertTrue(legend, legend.endsWith("\nSet API order puts your weather sources in this table's order; the top one becomes your primary source."))
        assertEquals("every legend column has text", DaySourceComparison.legendColumns(true).toSet(), LEGEND_TEXT.keys)
    }

    @Test
    fun `clicking a header sorts by that column, again reverses it, and the sort is reported for saving`() {
        // NWS: hotter but drier; Open-Meteo: cooler but wetter. Default (high) puts NWS first.
        seed("NWS", 70f, 50f, ageMs = 3_600_000L, dayMax = 10)
        seed("OPEN_METEO", 68f, 52f, ageMs = 2 * 3_600_000L, dayMax = 60)
        val tomorrow = LocalDate.now().plusDays(1)
        val saved = mutableListOf<String>()

        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = DesktopWeatherDao(db), config = config, initialDate = tomorrow, onClose = {},
                // The sort is the window's own save (never the source): read it off that channel.
                saves = DataWindowSaves(window = { saved += it.sourceCompareSort.orEmpty() }, sourceSwitch = {}),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        fun sourcesInOrder(): List<String> = (0..2).map {
            composeTestRule.onNodeWithTag("source_compare_row_$it", useUnmergedTree = true)
                .fetchSemanticsNode().children.first().config[SemanticsProperties.Text].joinToString()
        }.also { println("DesktopForecastComparisonSourcesTest order: $it saved=$saved") }

        assertEquals(listOf("NWS", "Meteo", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("High ▾").assertExists()

        composeTestRule.onNodeWithTag("source_compare_header_4", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("Meteo", "NWS", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("Day ▾").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()

        composeTestRule.onNodeWithTag("source_compare_header_4", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals("blank stays last when reversed", listOf("NWS", "Meteo", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("Day ▴").assertExists()
        assertEquals(listOf("DAY_RAIN:desc", "DAY_RAIN:asc"), saved)
    }

    @Test
    fun `a saved sort is applied when the window opens`() {
        seed("NWS", 70f, 50f, ageMs = 3_600_000L, dayMax = 10)
        seed("OPEN_METEO", 68f, 52f, ageMs = 2 * 3_600_000L, dayMax = 60)
        val tomorrow = LocalDate.now().plusDays(1)
        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = DesktopWeatherDao(db), config = config.copy(sourceCompareSort = "DAY_RAIN:desc"),
                initialDate = tomorrow, onClose = {},
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        val first = composeTestRule.onNodeWithTag("source_compare_row_0", useUnmergedTree = true)
            .fetchSemanticsNode().children.first().config[SemanticsProperties.Text].joinToString()
        println("DesktopForecastComparisonSourcesTest saved-sort first row: $first")
        assertEquals("Meteo", first)
        composeTestRule.onNodeWithText("Day ▾").assertExists()
    }

    @Test
    fun `today - a source whose later fetch left today out gets a starred age and the footnote`() {
        // Desktop 2026-11-13: Silurian today 12:10, tomorrow 16:59 (UTC days after 4 PM Pacific).
        seed("NWS", 63f, 54f, ageMs = 60_000L, dayOffset = 0)
        seed("NWS", 65f, 48f, ageMs = 60_000L, dayOffset = 1)
        seed("SILURIAN", 64f, 53f, ageMs = 5 * 3_600_000L, dayOffset = 0)
        seed("SILURIAN", 66f, 50f, ageMs = 12 * 60_000L, dayOffset = 1)
        composeTestRule.setContent {
            ForecastComparisonContent(weatherDao = DesktopWeatherDao(db), config = config, initialDate = LocalDate.now(), onClose = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("5h*").fetchSemanticsNodes().isNotEmpty()
        }
        val legend = composeTestRule.onNodeWithTag("source_compare_legend", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        println("DesktopForecastComparisonSourcesTest today legend: $legend")
        assertTrue(legend, legend.endsWith("grey after 24 h\n* $STAR_NOTE\n$APPLY_ORDER_LABEL $APPLY_ORDER_NOTE"))
        assertTrue("NWS is not starred", composeTestRule.onAllNodesWithText("1m").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `clicking Set API order saves reordered visibleSources through sourceOrder channel and disables button`() {
        // NWS: hotter (70°) but drier (10%); Open-Meteo: cooler (68°) but wetter (60%).
        seed("NWS", 70f, 50f, ageMs = 3_600_000L, dayMax = 10)
        seed("OPEN_METEO", 68f, 52f, ageMs = 2 * 3_600_000L, dayMax = 60)
        val tomorrow = LocalDate.now().plusDays(1)
        var liveConfig by mutableStateOf(config)
        val saveLabels = mutableListOf<String>()

        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = DesktopWeatherDao(db),
                config = liveConfig,
                initialDate = tomorrow,
                onClose = {},
                saves = DataWindowSaves.forForecastComparison { next, label ->
                    saveLabels += label
                    liveConfig = resolveDesktopConfigSave(liveConfig, next, label).config
                },
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }

        // Default High sort matches [NWS, OPEN_METEO, SILURIAN]: button starts disabled.
        composeTestRule.onNodeWithTag("source_compare_apply_order").assertIsNotEnabled()

        // Click Rain header -> [OPEN_METEO, NWS, SILURIAN]: button becomes enabled.
        composeTestRule.onNodeWithTag("source_compare_header_4", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("source_compare_apply_order").assertIsEnabled()

        // Click "Set API order": saves via COMPARISON_SOURCE_ORDER, keeps weatherSource, disables button.
        composeTestRule.onNodeWithTag("source_compare_apply_order").performClick()
        composeTestRule.waitForIdle()

        println("DesktopForecastComparisonSourcesTest applyOrder: sources=${liveConfig.settings.visibleSources} labels=$saveLabels")
        assertEquals(listOf("OPEN_METEO", "NWS", "SILURIAN"), liveConfig.settings.visibleSources)
        assertEquals("NWS", liveConfig.settings.weatherSource)
        assertEquals(ConfigSaveSource.COMPARISON_SOURCE_ORDER, saveLabels.last())
        composeTestRule.onNodeWithTag("source_compare_apply_order").assertIsNotEnabled()
    }

    @Test
    fun `graph mode toggle is hidden on today and shown on past days near the graphs`() {
        seed("NWS", 70f, 50f, ageMs = 3_600_000L, dayOffset = 0)
        seed("NWS", 66f, 48f, ageMs = 3_600_000L, dayOffset = -1)

        composeTestRule.setContent {
            ForecastComparisonContent(
                weatherDao = DesktopWeatherDao(db),
                config = config,
                initialDate = LocalDate.now(),
                onClose = {},
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("graph_mode_button").assertDoesNotExist()

        // Navigate back to yesterday: the mode toggle appears and switches Evolution <-> Error.
        composeTestRule.onNodeWithTag("prev_day_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Mode: Evolution").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("graph_mode_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Mode: Error").assertExists()
    }

    /**
     * The Now column (header rain %) through the real SQLite rows and the shared runner: today, a wide
     * table shows it under the "Rain" group; shrinking the same composition drops it, widening brings
     * it back (plans/261120-all-sources-header-rain-column.md).
     */
    @Test
    fun `today's Now column appears and disappears as the window width changes`() {
        seed("NWS", 66.4f, 50.2f, ageMs = 3_600_000L, noonCloud = 80, dayMax = 30, dayOffset = 0)
        seed("OPEN_METEO", 68.1f, 52.6f, ageMs = 2 * 3_600_000L, noonCloud = 40, dayMax = 70, dayOffset = 0)
        val today = LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val dao = DesktopWeatherDao(db)
        val fetched = System.currentTimeMillis()
        // Every hour from yesterday evening to tomorrow morning, so "now + 1 h" is covered at any test time.
        val hours = (-6..33).map { today.atStartOfDay(zone).plusHours(it.toLong()).toInstant().toEpochMilli() }
        for ((source, chance) in listOf("NWS" to 30, "OPEN_METEO" to 70)) {
            dao.upsertHourlyForecasts(
                lat, lon, source,
                hours.map {
                    com.weatherwidget.data.model.HourlyForecast(
                        it, 60f, "Cloudy", precipProbability = chance, source = source, fetchedAt = fetched,
                        locationLat = lat, locationLon = lon,
                    )
                },
            )
        }
        var widthDp by mutableStateOf(1000)
        composeTestRule.setContent {
            Box(Modifier.width(widthDp.dp)) {
                ForecastComparisonContent(weatherDao = dao, config = config, initialDate = today, onClose = {})
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        val nowTag = "source_compare_header_${DaySourceComparison.HEADER_RAIN_COLUMN}"
        fun shown(tag: String) =
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        fun rowTexts(i: Int): List<String> =
            composeTestRule.onNodeWithTag("source_compare_row_$i", useUnmergedTree = true)
                .fetchSemanticsNode().children.map { it.config[SemanticsProperties.Text].joinToString() }

        println("DesktopForecastComparisonSourcesTest wide rows: ${(0..2).map { rowTexts(it) }}")
        assertTrue("wide: Now shown", shown(nowTag))
        assertTrue("Rain group label", shown("source_compare_rain_group"))
        val meteo = (0..2).map { rowTexts(it) }.first { it.first() == "Meteo" }
        assertEquals("Meteo's Now = its header chance", "70%", meteo[3])
        val legend = composeTestRule.onNodeWithTag("source_compare_legend", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(legend, legend.startsWith("Now ${nowLegend(1)}\nDay "))
        // A look at the real rendering (Rain label vs Low), kept as a diagnostic.
        val shot = java.io.File("build/test-screens/source_compare_wide.png").apply { parentFile.mkdirs() }
        javax.imageio.ImageIO.write(
            composeTestRule.onRoot().captureToImage().toAwtImage(), "png", shot,
        )
        println("DesktopForecastComparisonSourcesTest screenshot: ${shot.absolutePath}")

        widthDp = 420
        composeTestRule.waitForIdle()
        println("DesktopForecastComparisonSourcesTest narrow rows: ${(0..2).map { rowTexts(it) }}")
        assertTrue("narrow: Now dropped", !shown(nowTag))
        assertTrue("Rain group still over Day..", shown("source_compare_rain_group"))
        assertTrue("Day column still shown", shown("source_compare_header_${DaySourceComparison.SortColumn.DAY_RAIN.ordinal}"))

        widthDp = 1000
        composeTestRule.waitForIdle()
        assertTrue("widened again: Now back", shown(nowTag))
    }
}
