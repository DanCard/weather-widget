package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.LongDuration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Integrated test verifying the desktop header layout inside [WidgetPopup] across scaled window
 * dimensions:
 * 1. The daily date remains visible at scaled window sizes (preventing regression where a double-counted
 *    gap in rightCluster forced HeaderFit to drop the date).
 * 2. The source API label is centered symmetrically between the date and the gear icon.
 * 3. The source API label maintains identical horizontal coordinates across Daily and Hourly views (no jumping).
 */
@Category(LongDuration::class)
class DesktopHeaderDateIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Dimensions reported by the user's desktop environment: 812dp x 701dp (uiScale ~ 2.19)
    private val windowWidth = 812.dp
    private val windowHeight = 701.dp

    private fun sampleForecast(): ForecastSnapshot {
        val zone = ZoneId.systemDefault()
        val hourStart = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val hourly = (0..9).map { h ->
            HourlyForecast(
                dateTime = hourStart.plusHours(h.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 71f,
                condition = "Cloudy",
                precipProbability = 0,
            )
        }
        val today = LocalDate.now()
        return ForecastSnapshot(
            raw = RawFetch(
                daily = listOf(
                    DailyForecast(today.toString(), 75f, 55f, "Cloudy", precipProbability = 0),
                    DailyForecast(today.plusDays(1).toString(), 78f, 58f, "Cloudy", precipProbability = 0),
                ),
                hourly = hourly,
            ),
            resolved = ResolvedView(currentTemp = 71f, currentCondition = "Cloudy", deltaFromYesterday = 2.2f),
        )
    }

    private val baseConfig = DesktopConfig(
        lat = 37.4166,
        lon = -122.0888,
        label = "Mountain View",
        settings = DesktopSettings(
            weatherSource = "NWS",
            visibleSources = listOf("NWS", "OPEN_METEO", "GOOGLE_WEATHER"),
            useCelsius = false,
        ),
        viewMode = ViewMode.DAILY,
    )

    private fun renderPopup(config: DesktopConfig) {
        composeTestRule.setContent {
            Box(Modifier.requiredSize(windowWidth, windowHeight)) {
                WidgetPopup(
                    config = config,
                    forecast = sampleForecast(),
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    resolvedCurrentTemp = 71f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onOpenHistory = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun dailyDateIsVisibleAndSourceLabelIsCenteredBetweenDateAndGear() {
        renderPopup(baseConfig.copy(viewMode = ViewMode.DAILY))

        // 1. Date must be present and displayed (regression test for HeaderFit dropping date at scale ~2.19)
        val dateNode = composeTestRule.onAllNodesWithTag("daily_header_date", useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()
        assertTrue("Daily header date must be visible in 812x701 window", dateNode != null)
        val dateBounds = dateNode!!.boundsInRoot

        // 2. Source API label and gear icon must be displayed
        val sourceBounds = composeTestRule.onAllNodesWithTag("header_source_label", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val gearBounds = composeTestRule.onAllNodesWithContentDescription("Settings", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot

        // 3. Source API label must be centered symmetrically between date and gear
        val leftGap = sourceBounds.left - dateBounds.right
        val rightGap = gearBounds.left - sourceBounds.right
        assertEquals(
            "Source label must be centered between date and gear: leftGap=$leftGap rightGap=$rightGap",
            leftGap,
            rightGap,
            2f,
        )
    }

    @Test
    fun sourceLabelMaintainsHorizontalPositionWhenSwitchingToHourly() {
        // Daily
        renderPopup(baseConfig.copy(viewMode = ViewMode.DAILY))
        val dailySourceBounds = composeTestRule.onAllNodesWithTag("header_source_label", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot

        // Hourly
        renderPopup(baseConfig.copy(viewMode = ViewMode.TEMPERATURE))
        val hourlySourceBounds = composeTestRule.onAllNodesWithTag("header_source_label", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot

        assertEquals(
            "Source label must maintain identical horizontal position across Daily and Hourly views (no jump)",
            dailySourceBounds.left,
            hourlySourceBounds.left,
            1.5f,
        )
    }

    @Test
    fun dailyDateRemainsVisibleWhenWindowIsMadeTaller() {
        // Taller window where uiScale grows to ~2.28 while width is 778dp
        composeTestRule.setContent {
            Box(Modifier.requiredSize(778.dp, 730.dp)) {
                WidgetPopup(
                    config = baseConfig.copy(viewMode = ViewMode.DAILY),
                    forecast = sampleForecast(),
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    resolvedCurrentTemp = 71f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateLocation = {},
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onOpenHistory = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val dateNode = composeTestRule.onAllNodesWithTag("daily_header_date", useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()
        assertTrue("Daily header date must remain visible on tall 778x730 window (uiScale ~ 2.28)", dateNode != null)
    }
}
