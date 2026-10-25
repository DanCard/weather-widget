package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.util.HeaderButtonZone
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
        // icon where the 44dp button pair cannot fit at all, the narrow "40%" where the 30sp
        // temperature cannot — the 100dp header). So no rain ≤ temp here; HeaderFitTest sweeps the
        // full order.
        assertTrue("buttons ≤ rain, buttons ≤ temp ($counts)", buttons <= rain && buttons <= temp)
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
        val bounds = composeTestRule.onAllNodesWithTag("open_forecast_history_daily_icon", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        // History, the last button, ends its zone on the line 70dp right of the middle (the icon half
        // the shared air before it); the date starts after it.
        val rightDp = with(composeTestRule.density) { bounds.right.toDp().value }
        assertEquals(LINE_ICON_RIGHT_DP, rightDp, 1f)
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
        val history = composeTestRule.onAllNodesWithTag("open_forecast_history_icon", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val rightDp = with(composeTestRule.density) { history.right.toDp().value }
        assertEquals(LINE_ICON_RIGHT_DP, rightDp, 1f)
        composeTestRule.onAllNodesWithTag("graph_selector", useUnmergedTree = true).fetchSemanticsNodes().single()
    }

    private fun header(
        config: DesktopConfig,
        headerTime: LocalDateTime = LocalDateTime.now(),
        scale: Float = 1f,
        widthDp: Int = 800,
    ) {
        composeTestRule.setContent {
            Box(Modifier.requiredWidth(widthDp.dp)) {
                WidgetHeader(
                    config = config,
                    forecast = snapshot(),
                    resolvedCurrentTemp = 64f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateConfig = {},
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onUpdateLocation = {},
                    headerTime = headerTime,
                    scale = scale,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Where a button's icon starts (its `_icon` tag; the plain tag is its wider click zone). */
    private fun leftDp(tag: String): Float? =
        composeTestRule.onAllNodesWithTag(tag + "_icon", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            ?.let { with(composeTestRule.density) { it.boundsInRoot.left.toDp().value } }

    @Test
    fun hourlyRowIsHomeObservationsSelectorHistory() {
        // plans/261022-home-button-first-shared-header-buttons.md: home first, history last.
        header(config.copy(viewMode = ViewMode.TEMPERATURE))
        val xs = listOf("switch_to_daily", "open_observations_header", "graph_selector", "open_forecast_history")
            .map { tag -> tag to (leftDp(tag) ?: error("$tag missing")) }
        assertEquals("left to right: $xs", xs.sortedBy { it.second }, xs)
    }

    @Test
    fun hourlyObservationsHideWhenTodayIsOffScreenAndOnlyHomeMoves() {
        header(config.copy(viewMode = ViewMode.TEMPERATURE), headerTime = LocalDateTime.now().plusHours(48))
        assertEquals("observations: no today on screen", null, leftDp("open_observations_header"))
        val history = composeTestRule.onAllNodesWithTag("open_forecast_history_icon", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assertEquals(LINE_ICON_RIGHT_DP, with(composeTestRule.density) { history.right.toDp().value }, 1f)
        assertTrue("home still leftmost", leftDp("switch_to_daily")!! < leftDp("graph_selector")!!)
    }

    @Test
    fun dailyHomeAppearsFirstWithoutMovingTheOtherButtons() {
        // Two headers, one above the other: today on screen (no home), and today panned off with
        // yesterday still there (home recentres; observations stays).
        composeTestRule.setContent {
            Column {
                for (todayInView in listOf(true, false)) {
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
                            todayInView = todayInView,
                            observationsInView = true,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        fun lefts(tag: String) = composeTestRule.onAllNodesWithTag(tag + "_icon", useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { with(composeTestRule.density) { it.boundsInRoot.left.toDp().value } }
        val home = lefts("daily_home_button")
        val obs = lefts("open_observations_header_daily")
        val history = lefts("open_forecast_history_daily")
        assertEquals("home only on the panned header", 1, home.size)
        assertEquals("observations in both", 2, obs.size)
        assertEquals("observations did not move", obs[0], obs[1], 0.5f)
        assertEquals("history did not move", history[0], history[1], 0.5f)
        assertTrue("home left of observations", home.single() < obs[1])
    }

    @Test
    fun aClickPastHistoryOpensHistoryUpToHalfwayToTheDate() {
        // The history zone reaches halfway from its slot to the date; nearer the date the click is
        // the header's source toggle, as before (user, 2026-10-25).
        val opened = mutableListOf<LocalDate>()
        val configChanges = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            Box(Modifier.requiredWidth(800.dp)) {
                WidgetHeader(
                    config = config,
                    forecast = snapshot(),
                    resolvedCurrentTemp = 64f,
                    resolvedDeltaFromYesterday = 2.2f,
                    onUpdateConfig = { configChanges += it },
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onOpenHistory = { opened += it },
                    onUpdateLocation = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        fun bounds(tag: String) = composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val zone = bounds("header_touch_extension_end")
        val date = bounds("daily_header_date")
        val slotRight = with(composeTestRule.density) { FLOATING_SLOT_RIGHT_DP.dp.toPx() }
        assertEquals(slotRight, zone.left, 1.5f)
        assertEquals((slotRight + date.left) / 2f, zone.right, 1.5f)

        composeTestRule.onNodeWithTag("header_touch_extension_end")
            .performTouchInput { click(Offset(width - 2f, height / 2f)) }
        composeTestRule.waitForIdle()
        assertEquals(1, opened.size)
        assertTrue("source untouched: $configChanges", configChanges.isEmpty())
    }

    @Test
    fun sourceLabelIsCenteredBetweenDateAndGearInDailyAndMatchesHourly() {
        // Daily view: source label centered between date and gear
        header(config.copy(viewMode = ViewMode.DAILY))
        val dateBounds = composeTestRule.onAllNodesWithTag("daily_header_date", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val dailySourceBounds = composeTestRule.onAllNodesWithTag("header_source_label", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        val gearBounds = composeTestRule.onAllNodesWithContentDescription("Settings", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot

        val leftGap = dailySourceBounds.left - dateBounds.right
        val rightGap = gearBounds.left - dailySourceBounds.right
        assertEquals("source label centered between date and gear: leftGap=$leftGap rightGap=$rightGap", leftGap, rightGap, 1.5f)

        // Hourly view: source label stays at the exact same horizontal position
        header(config.copy(viewMode = ViewMode.TEMPERATURE))
        val hourlySourceBounds = composeTestRule.onAllNodesWithTag("header_source_label", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assertEquals("hourly source label matches daily position exactly (no jump across views)", dailySourceBounds.left, hourlySourceBounds.left, 1f)
    }

    @Test
    fun dateRemainsVisibleOnScaledDesktopWindow() {
        // User's desktop window: ~778dp width, scale ~2.08
        header(config.copy(viewMode = ViewMode.DAILY), scale = 2.08f, widthDp = 778)
        val dateNodes = composeTestRule.onAllNodesWithTag("daily_header_date", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("date must remain visible on scaled desktop window", 1, dateNodes.size)
    }
}

/**
 * Where the last button's icon ends in an 800dp header at scale 1: its zone ends on HeaderFit's
 * floating seam line, and the icon sits centred in it, half the shared air short of that.
 */
private val FLOATING_SLOT_RIGHT_DP = 400f + 94f
private val LINE_ICON_RIGHT_DP = FLOATING_SLOT_RIGHT_DP - HeaderButtonZone.airDp(800f) / 2f
