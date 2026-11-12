package com.weatherwidget.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * History of Forecasts' "All sources for <day>" card through [ForecastHistoryContent] and the real
 * SQLite reads: sorted by high with Android's short names (`ForecastHistorySourceComparisonRoboTest`).
 * plans/261105-all-sources-day-comparison.md
 */
@Category(LongDuration::class)
class DesktopForecastHistorySourceComparisonTest {
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

    private fun seed(source: String, high: Float, low: Float, ageMs: Long, noonCloud: Int? = null, dayMax: Int? = null) {
        val today = LocalDate.now()
        val fetched = System.currentTimeMillis() - ageMs
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                    "isClimateNormal, source, lastConfirmedAt, fetchedAt, noonCloudPercent, hourlyDayPrecipMax) VALUES (?, ?, ?, ?, ?, ?, 'Cloudy', 0, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                listOf<Any?>(
                    today.plusDays(1).toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon, high, low,
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
            ForecastHistoryContent(weatherDao = DesktopWeatherDao(db), config = config, initialDate = tomorrow, onClose = {})
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
        println("DesktopForecastHistorySourceComparisonTest rows: $rows")
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
        println("DesktopForecastHistorySourceComparisonTest legend: $legend")
        assertTrue(legend, legend.startsWith("Rain highest hourly chance, 8am–8pm\nPeak hour of the highest daytime chance\n"))
        assertTrue(legend, legend.contains("\nCloud cloud cover at noon\n"))
        assertTrue(legend, legend.endsWith("Age time since the source last confirmed this forecast; grey after 24 h"))
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
            ForecastHistoryContent(
                weatherDao = DesktopWeatherDao(db), config = config, initialDate = tomorrow, onClose = {},
                onSourceCompareSort = { saved += it.encode() },
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        fun sourcesInOrder(): List<String> = (0..2).map {
            composeTestRule.onNodeWithTag("source_compare_row_$it", useUnmergedTree = true)
                .fetchSemanticsNode().children.first().config[SemanticsProperties.Text].joinToString()
        }.also { println("DesktopForecastHistorySourceComparisonTest order: $it saved=$saved") }

        assertEquals(listOf("NWS", "Meteo", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("High ▾").assertExists()

        composeTestRule.onNodeWithTag("source_compare_header_3", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("Meteo", "NWS", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("Rain ▾").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()

        composeTestRule.onNodeWithTag("source_compare_header_3", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals("blank stays last when reversed", listOf("NWS", "Meteo", "Silur"), sourcesInOrder())
        composeTestRule.onNodeWithText("Rain ▴").assertExists()
        assertEquals(listOf("DAY_RAIN:desc", "DAY_RAIN:asc"), saved)
    }

    @Test
    fun `a saved sort is applied when the window opens`() {
        seed("NWS", 70f, 50f, ageMs = 3_600_000L, dayMax = 10)
        seed("OPEN_METEO", 68f, 52f, ageMs = 2 * 3_600_000L, dayMax = 60)
        val tomorrow = LocalDate.now().plusDays(1)
        composeTestRule.setContent {
            ForecastHistoryContent(
                weatherDao = DesktopWeatherDao(db), config = config.copy(sourceCompareSort = "DAY_RAIN:desc"),
                initialDate = tomorrow, onClose = {},
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Silur").fetchSemanticsNodes().isNotEmpty()
        }
        val first = composeTestRule.onNodeWithTag("source_compare_row_0", useUnmergedTree = true)
            .fetchSemanticsNode().children.first().config[SemanticsProperties.Text].joinToString()
        println("DesktopForecastHistorySourceComparisonTest saved-sort first row: $first")
        assertEquals("Meteo", first)
        composeTestRule.onNodeWithText("Rain ▾").assertExists()
    }
}
