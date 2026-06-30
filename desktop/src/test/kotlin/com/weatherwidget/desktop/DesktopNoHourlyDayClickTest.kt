package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Verifies the two-phase "no hourly data" day-tap flow on the desktop daily view:
 *   1. Tapping a future day with no hourly data shows a "refresh will be triggered" banner and
 *      triggers a refresh (instead of switching to a black hourly graph).
 *   2. When the refresh returns data, a "now available" result banner replaces the pending one.
 *   3. When the refresh returns nothing, a "no hourly data" result banner replaces it.
 *
 * The refresh is driven through [WidgetPopup]'s `onNeedHourlyRefresh` callback, which the test
 * captures and invokes manually to simulate completion — no real network or DB.
 */
class DesktopNoHourlyDayClickTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today: LocalDate = LocalDate.now()
    // today+7 is reliably within a 9-column daily window (offsets -1..+7) and far past the near-term
    // hourly coverage in the stub, so it always reads as "no hourly data for this day".
    private val targetDate: LocalDate = today.plusDays(7)

    private fun epochMs(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Daily entries covering today..today+7 so every column has a high/low and is clickable. */
    private val dailyEntries: List<DailyForecast> = (0..7).map { offset ->
        val d = today.plusDays(offset.toLong())
        DailyForecast(d.toString(), 75f + offset, 55f + offset, "Sunny", precipProbability = 0)
    }

    /** Hourly data ONLY for today — so today+7 has none. */
    private val nearTermHourly: List<HourlyForecast> = listOf(
        HourlyForecast(epochMs(today, 9), 70f, "Sunny", source = WeatherSource.NWS.id),
        HourlyForecast(epochMs(today, 12), 74f, "Sunny", source = WeatherSource.NWS.id),
    )

    private val stubForecast = ForecastResult(
        currentTemp = 72f,
        currentCondition = "Sunny",
        daily = dailyEntries,
        hourly = nearTermHourly,
    )

    private val stubConfig = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        visibleSources = listOf("NWS"),
    )

    /** Hourly data that a successful refresh would return for the target day (≥2 NWS points). */
    private val refreshedHourly: List<HourlyForecast> = nearTermHourly + listOf(
        HourlyForecast(epochMs(targetDate, 9), 68f, "Sunny", source = WeatherSource.NWS.id),
        HourlyForecast(epochMs(targetDate, 15), 73f, "Sunny", source = WeatherSource.NWS.id),
    )

    private fun renderTextModeDaily(
        onUpdateConfig: (DesktopConfig) -> Unit = {},
        onNeedHourlyRefresh: (Int, (List<HourlyForecast>) -> Unit) -> Unit = { _, _ -> },
    ) {
        composeTestRule.setContent {
            // 600dp wide → 9 day columns (offsets -1..+7); short height → text mode (no graph),
            // so each day renders as a clickable semantic node tagged "day_tab_<date>".
            Box(Modifier.size(600.dp, 110.dp)) {
                WidgetPopup(
                    config = stubConfig,
                    forecast = stubForecast,
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    onUpdateLocation = {},
                    onUpdateConfig = onUpdateConfig,
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onNeedHourlyRefresh = onNeedHourlyRefresh,
                )
            }
        }
    }

    @Test
    fun pendingMessageShownAndRefreshTriggeredOnNoHourlyDayClick() {
        var capturedComplete: ((List<HourlyForecast>) -> Unit)? = null
        var pushedConfig: DesktopConfig? = null
        renderTextModeDaily(
            onUpdateConfig = { pushedConfig = it },
            onNeedHourlyRefresh = { _, onComplete -> capturedComplete = onComplete },
        )

        composeTestRule.onNodeWithTag("day_tab_$targetDate").performClick()
        composeTestRule.waitForIdle()

        // Phase 1: pending banner shown, refresh registered, view stayed on daily (no HOURLY switch).
        composeTestRule.onNodeWithText("Hourly data missing", substring = true).assertIsDisplayed()
        assert(capturedComplete != null) { "expected a refresh to be triggered" }
        assert(pushedConfig?.viewMode != ViewMode.HOURLY) { "should not switch to hourly view" }
        composeTestRule.onNodeWithTag("day_tab_$targetDate").assertIsDisplayed()
    }

    @Test
    fun resultMessageShownWhenRefreshReturnsData() {
        var capturedComplete: ((List<HourlyForecast>) -> Unit)? = null
        renderTextModeDaily(
            onNeedHourlyRefresh = { _, onComplete -> capturedComplete = onComplete },
        )

        composeTestRule.onNodeWithTag("day_tab_$targetDate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hourly data missing", substring = true).assertIsDisplayed()

        // Phase 2 (data arrived): result banner replaces the pending one.
        composeTestRule.runOnIdle { capturedComplete!!(refreshedHourly) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Results of refresh", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("now available", substring = true).assertIsDisplayed()
    }

    @Test
    fun resultMessageShownWhenRefreshReturnsNoData() {
        var capturedComplete: ((List<HourlyForecast>) -> Unit)? = null
        renderTextModeDaily(
            onNeedHourlyRefresh = { _, onComplete -> capturedComplete = onComplete },
        )

        composeTestRule.onNodeWithTag("day_tab_$targetDate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hourly data missing", substring = true).assertIsDisplayed()

        // Phase 2 (still nothing): refresh returns the same near-term hourly with no target-day points.
        composeTestRule.runOnIdle { capturedComplete!!(nearTermHourly) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Results of refresh", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("No hourly data", substring = true).assertIsDisplayed()
    }
}
