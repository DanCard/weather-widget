package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * After a switch the previous source's graph may stay under the new label only briefly. A source with no
 * saved rows loads as null, which used to keep it there for as long as the fetch took, or for good.
 */
@Category(LongDuration::class)
class DesktopSourceWaitTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val nws = WeatherSource.NWS
    private val meteo = WeatherSource.OPEN_METEO
    private val loading = DesktopSourceWait.LOADING.message(meteo.displayName)
    private val noData = DesktopSourceWait.NO_DATA.message(meteo.displayName)

    private fun config(source: WeatherSource, viewMode: ViewMode) = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        viewMode = viewMode,
        settings = DesktopSettings(
            useCelsius = false,
            weatherSource = source.id,
            visibleSources = listOf(nws.id, meteo.id),
        ),
    )

    private fun snapshot(source: WeatherSource): ForecastSnapshot {
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val zone = ZoneId.systemDefault()
        return ForecastSnapshot(
            sourceId = source.id,
            raw = RawFetch(
                hourly = (-12L..72L).map { h ->
                    HourlyForecast(
                        dateTime = hourStart.plusHours(h).atZone(zone).toInstant().toEpochMilli(),
                        temperature = 64f,
                        condition = "Clear",
                        source = source.id,
                        precipProbability = 40,
                    )
                },
                daily = listOf(DailyForecast(LocalDate.now().toString(), 66f, 47f, "Clear", precipProbability = 40, source = source.id)),
            ),
            resolved = ResolvedView(currentTemp = 64f, currentCondition = "Clear"),
        )
    }

    private fun empty(source: WeatherSource) = ForecastSnapshot(source.id, RawFetch(), ResolvedView())

    private var shownConfig by mutableStateOf(config(nws, ViewMode.TEMPERATURE))
    private var forecast by mutableStateOf(snapshot(nws))
    private var noDataSourceId by mutableStateOf<String?>(null)

    private fun render(viewMode: ViewMode = ViewMode.TEMPERATURE) {
        shownConfig = config(nws, viewMode)
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                WidgetPopup(
                    config = shownConfig,
                    forecast = forecast,
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    noDataSourceId = noDataSourceId,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun advance(ms: Long) {
        composeTestRule.mainClock.advanceTimeBy(ms)
        composeTestRule.waitForIdle()
    }

    private fun assertNoMessage() {
        composeTestRule.onAllNodesWithText(loading).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(noData).assertCountEquals(0)
    }

    private fun assertGraphShown(tag: String, shown: Boolean) {
        composeTestRule.onAllNodesWithTag(tag).assertCountEquals(if (shown) 1 else 0)
    }

    @Test
    fun `a normal switch shows no message and keeps the graph`() {
        render()
        shownConfig = config(meteo, ViewMode.TEMPERATURE)
        composeTestRule.waitForIdle()
        advance(DesktopSourceWait.SLOW_LOAD_MS - 500)
        assertNoMessage()
        assertGraphShown("hourly_temperature_surface", true)

        forecast = snapshot(meteo)
        composeTestRule.waitForIdle()
        advance(1_000)
        assertNoMessage()
        assertGraphShown("hourly_temperature_surface", true)
    }

    @Test
    fun `a slow load blanks the graph at the threshold and the data brings it back`() {
        render()
        shownConfig = config(meteo, ViewMode.TEMPERATURE)
        composeTestRule.waitForIdle()
        advance(DesktopSourceWait.SLOW_LOAD_MS + 200)

        composeTestRule.onNodeWithText(loading).assertIsDisplayed()
        assertGraphShown("hourly_temperature_surface", false)

        forecast = snapshot(meteo)
        composeTestRule.waitForIdle()
        assertNoMessage()
        assertGraphShown("hourly_temperature_surface", true)
    }

    @Test
    fun `the daily view blanks too and the source button stays`() {
        render(ViewMode.DAILY)
        shownConfig = config(meteo, ViewMode.DAILY)
        composeTestRule.waitForIdle()
        advance(DesktopSourceWait.SLOW_LOAD_MS + 200)

        composeTestRule.onNodeWithText(loading).assertIsDisplayed()
        assertGraphShown("daily_forecast_surface", false)
        composeTestRule.onAllNodesWithTag("header_source_label").assertCountEquals(1)
        // The old source's 40% must not stay in the header under the new label.
        composeTestRule.onAllNodesWithText("40%", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a source with no rows says loading until its fetch ends`() {
        render()
        shownConfig = config(meteo, ViewMode.TEMPERATURE)
        forecast = empty(meteo)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(loading).assertIsDisplayed()
        assertGraphShown("hourly_temperature_surface", false)

        noDataSourceId = meteo.id
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(noData).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("header_source_label").assertCountEquals(1)

        forecast = snapshot(meteo)
        composeTestRule.waitForIdle()
        assertNoMessage()
        assertGraphShown("hourly_temperature_surface", true)
    }

    @Test
    fun `the rule`() {
        fun resolve(selected: Boolean, slow: Boolean = false, empty: Boolean = false, ended: Boolean = false) =
            DesktopSourceWait.resolve(selected, slow, empty, ended)

        assertNull("settled", resolve(selected = true))
        assertNull("old graph, briefly", resolve(selected = false))
        assertEquals(DesktopSourceWait.LOADING, resolve(selected = false, slow = true))
        assertEquals(DesktopSourceWait.LOADING, resolve(selected = true, empty = true))
        assertEquals(DesktopSourceWait.NO_DATA, resolve(selected = true, empty = true, ended = true))
        assertNull("rows win over a stale report", resolve(selected = true, ended = true))
    }
}
