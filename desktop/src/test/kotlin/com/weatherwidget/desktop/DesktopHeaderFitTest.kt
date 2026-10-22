package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The desktop header (daily and hourly) asks the shared drop order (`HeaderFit`) instead of keeping every item
 * and squeezing the centre (`plans/261022-shared-header-fit-priority.md`). One header per width,
 * from roomy to far too narrow; each item is counted across them.
 */
@Category(LongDuration::class)
class DesktopHeaderFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val widths = (100..800 step 25).toList()

    private fun snapshot(): ForecastSnapshot {
        val zone = ZoneId.systemDefault()
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // Source-less rows match any display source: a flat 40 % across the lookahead.
        val hourly = (0..9).map { h ->
            HourlyForecast(
                dateTime = hourStart.plusHours(h.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 64f,
                condition = "Cloudy",
                precipProbability = 40,
            )
        }
        val today = LocalDate.now()
        return ForecastSnapshot(
            raw = RawFetch(
                daily = listOf(DailyForecast(today.toString(), 75f, 55f, "Cloudy", precipProbability = 40)),
                hourly = hourly,
            ),
            resolved = ResolvedView(currentTemp = 64f, currentCondition = "Cloudy", deltaFromYesterday = 2.2f),
        )
    }

    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        settings = DesktopSettings(useCelsius = false),
        viewMode = ViewMode.DAILY,
    )

    @Test
    fun narrowingTheHeaderDropsItemsInPriorityOrder() {
        composeTestRule.setContent {
            Column {
                widths.forEach { width ->
                    Box(Modifier.requiredWidth(width.dp)) {
                        WidgetHeader(
                            config = config,
                            forecast = snapshot(),
                            resolvedCurrentTemp = 64f,
                            resolvedDeltaFromYesterday = 2.2f,
                            onUpdateConfig = {},
                            onOpenSettings = {},
                            onOpenObservations = {},
                            onUpdateLocation = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        fun tag(t: String) = composeTestRule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
        fun text(t: String) = composeTestRule.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().size
        val temp = text("64.0°")
        val rain = text("40%")
        val buttons = tag("open_forecast_history_daily")
        val icon = tag("header_weather_icon")
        val date = tag("daily_header_date")
        val counts = "temp=$temp rain=$rain buttons=$buttons icon=$icon date=$date of ${widths.size}"

        assertEquals("\"from yest\" never beside a rain % ($counts)", 0, text("from yest"))
        assertTrue("the widest header shows everything ($counts)", date >= 1)
        assertTrue("narrow headers must drop something, not squeeze ($counts)", icon < widths.size)
        // Counts cannot say more: a lower item may take room a higher one could not use (the 18dp
        // icon where the 44dp button pair cannot fit at all). HeaderFitTest sweeps the full order.
        assertTrue("buttons ≤ rain ≤ temp ($counts)", buttons <= rain && rain <= temp)
        assertTrue("narrow headers must drop the buttons, not squeeze them ($counts)", buttons < widths.size)
    }

    @Test
    fun buttonsSitRightOfTheMiddle() {
        // HeaderFit's line, not centred in the leftover span (single-source config: no home).
        composeTestRule.setContent {
            Box(Modifier.requiredWidth(800.dp)) {
                WidgetHeader(
                    config = config,
                    forecast = snapshot(),
                    resolvedCurrentTemp = 64f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onUpdateLocation = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val bounds = composeTestRule.onAllNodesWithTag("open_forecast_history_daily", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        // History, the last button, ends on the line 70dp right of the middle; the date starts after it.
        val rightDp = with(composeTestRule.density) { bounds.right.toDp().value }
        assertEquals(400f + HeaderFit.SEAM_OFFSET_DP, rightDp, 1f)
        val date = composeTestRule.onAllNodesWithTag("daily_header_date", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assertTrue("date at ${date.left} after the buttons' ${bounds.right}", date.left > bounds.right)
    }

    @Test
    fun hourlyButtonsEndOnTheSameLineSoHistoryStaysPutAcrossViews() {
        composeTestRule.setContent {
            Box(Modifier.requiredWidth(800.dp)) {
                WidgetHeader(
                    config = config.copy(viewMode = ViewMode.TEMPERATURE),
                    forecast = snapshot(),
                    resolvedCurrentTemp = 64f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onUpdateLocation = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val history = composeTestRule.onAllNodesWithTag("open_forecast_history", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val rightDp = with(composeTestRule.density) { history.right.toDp().value }
        assertEquals(400f + HeaderFit.SEAM_OFFSET_DP, rightDp, 1f)
        composeTestRule.onAllNodesWithTag("graph_selector", useUnmergedTree = true).fetchSemanticsNodes().single()
    }
}
