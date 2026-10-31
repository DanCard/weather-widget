package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The 2026-10-31 40% flash: config moves on the click, the snapshot when its load returns. The header
 * read the new source's hours from the old snapshot, found none, and showed the old source's daily
 * rain chance under the new label. The label now moves first and the rest with the snapshot
 * (plans/261031-desktop-header-source-label-first.md).
 */
@Category(LongDuration::class)
class DesktopHeaderSourceLabelFirstTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val tomorrow = WeatherSource.TOMORROW_IO
    private val meteo = WeatherSource.OPEN_METEO

    private fun snapshot(source: WeatherSource, hourlyRain: Int, dailyRain: Int): ForecastSnapshot {
        val zone = ZoneId.systemDefault()
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        return ForecastSnapshot(
            sourceId = source.id,
            raw = RawFetch(
                daily = listOf(
                    DailyForecast(LocalDate.now().toString(), 66f, 47f, "Cloudy", precipProbability = dailyRain, source = source.id),
                ),
                hourly = (0..9).map { h ->
                    HourlyForecast(
                        dateTime = hourStart.plusHours(h.toLong()).atZone(zone).toInstant().toEpochMilli(),
                        temperature = 64f,
                        condition = "Clear",
                        source = source.id,
                        precipProbability = hourlyRain,
                    )
                },
            ),
            resolved = ResolvedView(currentTemp = 64f, currentCondition = "Clear", deltaFromYesterday = 2.2f),
        )
    }

    private fun config(source: WeatherSource) = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        settings = DesktopSettings(
            useCelsius = false,
            weatherSource = source.id,
            visibleSources = listOf(tomorrow.id, meteo.id),
        ),
    )

    private fun hasText(text: String) =
        composeTestRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the label moves on the click and the rest with the snapshot`() {
        var shownConfig by mutableStateOf(config(tomorrow))
        var forecast by mutableStateOf(snapshot(tomorrow, hourlyRain = 0, dailyRain = 40))
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                WidgetPopup(
                    config = shownConfig,
                    forecast = forecast,
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    resolvedCurrentTemp = 64f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("before the click", hasText(tomorrow.shortDisplayName))

        shownConfig = config(meteo)
        composeTestRule.waitForIdle()

        assertTrue("the label moved", hasText(meteo.shortDisplayName))
        composeTestRule.onAllNodesWithText("40%", useUnmergedTree = true).assertCountEquals(0)

        forecast = snapshot(meteo, hourlyRain = 7, dailyRain = 2)
        composeTestRule.waitForIdle()

        assertTrue("the rest followed its snapshot", hasText("7%"))
    }
}
