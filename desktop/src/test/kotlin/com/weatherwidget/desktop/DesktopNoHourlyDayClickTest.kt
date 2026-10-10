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
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * The desktop day-tap on a day whose hourly data is missing
 * (`plans/261009-google-hourly-on-demand-past-72h.md`):
 *   - Google (72 h of hourly stored): the day's hourly view opens at once, empty, under a
 *     "Fetching hourly forecast for …" banner while `onNeedHourlyRefresh` fetches that day; the banner
 *     clears when the hours arrive, or turns into the "no hourly data" result when they don't.
 *   - Every source stores 72 h routinely since 2026-10-10, so an NWS day past that is fetched the
 *     same way; once a fresh on-demand fetch has stored NWS's whole horizon and it still ends before
 *     the day, the hourly view opens with the "no hourly forecast" message and nothing is fetched
 *     (`performance/261010-daily-view-summaries-instead-of-far-hourly.md`).
 *
 * The fetch is [WidgetPopup]'s `onNeedHourlyRefresh` callback, captured and completed by hand — no
 * real network or DB.
 */
@Category(MediumDuration::class)
class DesktopNoHourlyDayClickTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today: LocalDate = LocalDate.now()
    // Within a 9-column daily window (offsets -1..+7) and the 240 h on-demand reach, and past the
    // today's hourly in the stub.
    private val targetDate: LocalDate = today.plusDays(5)

    private fun epochMs(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Daily entries covering today..today+7 so every column has a high/low and is clickable. */
    private val dailyEntries: List<DailyForecast> = (0..7).map { offset ->
        val d = today.plusDays(offset.toLong())
        DailyForecast(d.toString(), 75f + offset, 55f + offset, "Sunny", precipProbability = 0)
    }

    private fun hourly(source: WeatherSource, vararg at: Pair<LocalDate, Int>): List<HourlyForecast> =
        at.map { (date, hour) ->
            HourlyForecast(epochMs(date, hour), 70f + hour / 4f, "Sunny", source = source.id, fetchedAt = System.currentTimeMillis())
        }

    private fun config(source: WeatherSource) = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf(source.id), weatherSource = source.id),
    )

    private val google = WeatherSource.GOOGLE_WEATHER

    /** NWS after a fresh on-demand fetch whose data ends on day 4, before [targetDate] (day 5). */
    private val nwsWholeHorizonEndingDay4 =
        hourly(WeatherSource.NWS, today to 9, today to 12, today.plusDays(4) to 0, today.plusDays(4) to 12)
    private val googleToday = hourly(google, today to 9, today to 12)

    private var shownConfig by mutableStateOf<DesktopConfig?>(null)
    private var fetchedDate: LocalDate? = null
    private var completeFetch: ((List<HourlyForecast>) -> Unit)? = null

    private fun render(source: WeatherSource, stored: List<HourlyForecast>, tap: Boolean = true, initial: DesktopConfig? = null) {
        shownConfig = initial ?: config(source)
        composeTestRule.setContent {
            // 600dp wide → 9 day columns (offsets -1..+7); short height → text mode (no graph),
            // so each day renders as a clickable semantic node tagged "day_tab_<date>".
            Box(Modifier.size(600.dp, 110.dp)) {
                WidgetPopup(
                    config = shownConfig!!,
                    forecast = ForecastSnapshot(
                        raw = RawFetch(daily = dailyEntries, hourly = stored),
                        resolved = ResolvedView(currentTemp = 72f, currentCondition = "Sunny"),
                    ),
                    dataStatus = DataStatus.Live(System.currentTimeMillis()),
                    onUpdateLocation = {},
                    onUpdateConfig = { shownConfig = it },
                    onOpenSettings = {},
                    onOpenObservations = {},
                    onNeedHourlyRefresh = { date, onComplete ->
                        fetchedDate = date
                        completeFetch = onComplete
                    },
                )
            }
        }
        if (tap) {
            composeTestRule.onNodeWithTag("day_tab_$targetDate").performClick()
            composeTestRule.waitForIdle()
        }
    }

    /** The hourly view resting on [date]'s noon, reached by ‹ ›, a drag or reopening — no tap. */
    private fun renderHourlyAt(source: WeatherSource, stored: List<HourlyForecast>, date: LocalDate) {
        val hoursToNoon = java.time.Duration.between(java.time.LocalDateTime.now(), date.atTime(12, 0)).toHours().toInt()
        render(source, stored, tap = false, initial = config(source).copy(viewMode = ViewMode.TEMPERATURE, hourlyOffset = hoursToNoon))
    }

    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(HourlyOnDemandPanSettleMs + 200)
        composeTestRule.waitForIdle()
    }

    private val HourlyOnDemandPanSettleMs = com.weatherwidget.data.remote.HourlyOnDemand.PAN_SETTLE_MS

    @Test
    fun panningOntoAnUncoveredGoogleDayFetchesItOnceSettled() {
        renderHourlyAt(google, googleToday, targetDate)
        assertNull("nothing before the view has settled", fetchedDate)

        settle()

        assertEquals(targetDate, fetchedDate)
        composeTestRule.onNodeWithText("Fetching hourly forecast for", substring = true).assertIsDisplayed()
    }

    @Test
    fun panningOntoACoveredDayDoesNothing() {
        renderHourlyAt(google, googleToday + hourly(google, targetDate to 0, targetDate to 12, targetDate to 23), targetDate)
        settle()

        assertNull(fetchedDate)
        composeTestRule.onNodeWithTag("no_hourly_message").assertDoesNotExist()
    }

    @Test
    fun panningOntoAnNwsDayPastItsDataSaysWhereItEnds() {
        renderHourlyAt(WeatherSource.NWS, nwsWholeHorizonEndingDay4, targetDate)
        settle()

        assertNull(fetchedDate)
        composeTestRule.onNodeWithText("No hourly forecast for", substring = true).assertIsDisplayed()
    }

    @Test
    fun googleDayPastStoredHoursOpensEmptyHourlyViewAndFetchesIt() {
        render(google, googleToday)

        assert(shownConfig!!.viewMode.isHourly) { "expected the hourly view, got ${shownConfig!!.viewMode}" }
        composeTestRule.onNodeWithTag("hourly_temperature_surface").assertIsDisplayed()
        composeTestRule.onNodeWithTag("no_hourly_message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fetching hourly forecast for", substring = true).assertIsDisplayed()
        assertEquals(targetDate, fetchedDate)
    }

    @Test
    fun fetchingBannerClearsWhenTheDaysHoursArrive() {
        render(google, googleToday)

        composeTestRule.runOnIdle { completeFetch!!(googleToday + hourly(google, targetDate to 9, targetDate to 15)) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("no_hourly_message").assertDoesNotExist()
        composeTestRule.onNodeWithTag("hourly_temperature_surface").assertIsDisplayed()
    }

    @Test
    fun fetchThatBringsNothingShowsTheResult() {
        render(google, googleToday)

        composeTestRule.runOnIdle { completeFetch!!(googleToday) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No hourly data", substring = true).assertIsDisplayed()
    }

    @Test
    fun googleDayAlreadyStoredToItsLastHourFetchesNothing() {
        render(google, googleToday + hourly(google, targetDate to 0, targetDate to 12, targetDate to 23))

        assert(shownConfig!!.viewMode.isHourly)
        assertNull(fetchedDate)
        composeTestRule.onNodeWithTag("no_hourly_message").assertDoesNotExist()
    }

    @Test
    fun everyHourlyGraphRendersAnEmptyWindow() {
        render(google, googleToday)
        val hoursToTargetNoon = java.time.Duration.between(java.time.LocalDateTime.now(), targetDate.atTime(12, 0)).toHours().toInt()
        for (mode in listOf(ViewMode.TEMPERATURE, ViewMode.PRECIPITATION, ViewMode.CLOUD_COVER)) {
            shownConfig = config(google).copy(viewMode = mode, hourlyOffset = hoursToTargetNoon)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("hourly_temperature_surface").assertIsDisplayed()
        }
    }

    @Test
    fun nwsDayWithNoHourlyOpensHourlyViewWithoutFetching() {
        render(WeatherSource.NWS, nwsWholeHorizonEndingDay4)

        assert(shownConfig!!.viewMode.isHourly)
        assertNull("a fresh fetch already stored NWS's whole horizon; nothing to fetch", fetchedDate)
        composeTestRule.onNodeWithText("No hourly forecast for", substring = true).assertIsDisplayed()
    }

    /** Stored to 72 h only, NWS's day 5 is on demand like Google's. */
    @Test
    fun nwsDayPastTheRoutine72hIsFetchedOnTap() {
        render(WeatherSource.NWS, hourly(WeatherSource.NWS, today to 9, today to 12))

        assert(shownConfig!!.viewMode.isHourly)
        assertEquals(targetDate, fetchedDate)
        composeTestRule.onNodeWithText("Fetching hourly forecast for", substring = true).assertIsDisplayed()
    }
}
