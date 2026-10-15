package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.months.DailyZoomDensity
import com.weatherwidget.shared.months.MonthsDayValue
import com.weatherwidget.shared.months.MonthsSpan
import com.weatherwidget.shared.months.MonthsZoomAction
import com.weatherwidget.shared.months.MonthsZoomController
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay

/**
 * The desktop months view end to end through its real collaborators: the daily model
 * ([DesktopDailyForecastModel]), the shared zoom rules ([MonthsZoomController]) and the config glue
 * ([DesktopMonthsModel]) — the same calls the popup makes for each wheel notch and click.
 */
@Category(ShortDuration::class)
class DesktopMonthsModelTest {

    private val now = LocalDateTime.of(2026, 10, 15, 12, 0)
    private val today = now.toLocalDate()
    private val widthDp = 500
    private val dimensions = DesktopDailyForecastModel.dimensions(widthDp = widthDp, heightDp = 300)

    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Test",
        settings = DesktopSettings(weatherSource = "NWS"),
    )

    private fun actual(date: LocalDate, high: Float?, low: Float?, fHigh: Float? = null, fLow: Float? = null) = DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = "NWS",
        locationLat = 37.4220,
        locationLon = -122.0841,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = "Clear",
        updatedAt = 1L,
        forecastHighTemp = fHigh,
        forecastLowTemp = fLow,
    )

    /** 200 days of history (one gap, one forecast-only day), 10 days of forecast, a climate filler row. */
    private fun snapshot(): ForecastSnapshot {
        val actuals = (1L..200L)
            .filter { it != 50L }
            .associate { n ->
                val date = today.minusDays(n)
                date.toString() to if (n == 20L) actual(date, null, null, 70f, 50f) else actual(date, 60f + n % 10, 40f + n % 6)
            }
        val daily = (0L..9L).map { n -> DailyForecast(today.plusDays(n).toString(), 72f, 52f, "Clear", source = "NWS") } +
            DailyForecast(today.plusDays(20).toString(), 65f, 45f, "Clear", isClimateNormal = true)
        return ForecastSnapshot(
            raw = RawFetch(daily = daily, dailyActuals = actuals, climateNormals = mapOf(MonthDay.from(today) to (68f to 48f))),
            resolved = ResolvedView(currentTemp = 70f),
        )
    }

    private fun daily(cfg: DesktopConfig, snap: ForecastSnapshot) =
        DesktopDailyForecastModel.build(cfg, snap, dimensions, now, currentTemp = 70f)

    /** One wheel notch out in the daily view, exactly as the popup handles it. */
    private fun dailyNotchOut(cfg: DesktopConfig, snap: ForecastSnapshot): DesktopConfig {
        val state = daily(cfg, snap)
        val action = MonthsZoomController.dailyZoomOut(widthDp.toFloat(), state.days.size, state.canZoomOut, state.days.last().date)
        return if (action == MonthsZoomAction.AddHistoryColumn) {
            cfg.copy(dailyExtraHistory = state.clampedExtraHistory + 1)
        } else {
            DesktopMonthsModel.apply(action, cfg, today, dimensions)
        }
    }

    @Test
    fun `months state reads actuals, frozen-forecast stand-ins, the forecast tail and normals`() {
        val state = DesktopMonthsModel.build(config, snapshot(), today)
        assertEquals(MonthsSpan.TWO, state.span)
        // Opens on the forecast tail; the climate filler row is not forecast.
        assertEquals(today.plusDays(9), state.window.end)
        assertEquals(MonthsDayValue.Kind.PAST_FORECAST, state.values.single { it.date == today.minusDays(20) }.kind)
        assertFalse(state.values.any { it.date == today.minusDays(50) })
        assertEquals(today.minusDays(200)..today.plusDays(9), state.bounds)
        assertEquals(1, state.normals.size)
    }

    @Test
    fun `wheel out adds columns until too dense, then opens one month on the daily view's right edge`() {
        val snap = snapshot()
        var cfg = config
        var notches = 0
        while (!cfg.monthsView && notches < 40) {
            val before = daily(cfg, snap)
            cfg = dailyNotchOut(cfg, snap)
            notches++
            if (cfg.monthsView) {
                assertFalse("hand-off happens while columns still fit", DailyZoomDensity.tooDense(widthDp.toFloat(), before.days.size))
                assertTrue(DailyZoomDensity.tooDense(widthDp.toFloat(), before.days.size + 1) || !before.canZoomOut)
                assertEquals(before.days.last().date.toString(), cfg.monthsEnd)
            }
        }
        assertTrue(cfg.monthsView)
        assertEquals(1, cfg.monthsSpan)
    }

    @Test
    fun `wheel is reversible - out through the spans and back to the same daily right edge`() {
        val snap = snapshot()
        var cfg = config
        while (!cfg.monthsView) cfg = dailyNotchOut(cfg, snap)
        val dailyEdge = LocalDate.parse(cfg.monthsEnd)
        val spans = mutableListOf(cfg.monthsSpan)
        repeat(4) {
            val state = DesktopMonthsModel.build(cfg, snap, today)
            cfg = DesktopMonthsModel.apply(MonthsZoomController.monthsZoomOut(state.span), cfg, today, dimensions)
            spans += cfg.monthsSpan
        }
        assertEquals(listOf(1, 2, 6, 12, 12), spans)

        repeat(4) {
            val state = DesktopMonthsModel.build(cfg, snap, today)
            cfg = DesktopMonthsModel.apply(MonthsZoomController.monthsZoomIn(state.span, state.window), cfg, today, dimensions)
        }
        assertFalse(cfg.monthsView)
        assertEquals(dailyEdge, daily(cfg, snap).days.last().date)
    }

    @Test
    fun `click opens the daily view centred on the clicked day, months back`() {
        val snap = snapshot()
        val cfg = config.copy(monthsView = true, monthsSpan = 6, dailyExtraHistory = 5)
        val clicked = today.minusDays(120)
        val next = DesktopMonthsModel.apply(MonthsZoomController.monthsTap(clicked), cfg, today, dimensions)
        assertFalse(next.monthsView)
        assertEquals(0, next.dailyExtraHistory)
        val days = daily(next, snap).days
        // The daily model must not clamp the jump away: the whole record is navigable.
        assertEquals(clicked, days[(days.size - 1) / 2].date)
        assertEquals(60f + 120 % 10, days.single { it.date == clicked }.solidHigh!!, 0f)
    }

    @Test
    fun `click lands mid-window near today too, where the Today overlay drops a column`() {
        val snap = snapshot()
        val cfg = config.copy(monthsView = true)
        for (back in listOf(30L, 4L, 1L, 0L, -2L)) {
            val clicked = today.minusDays(back)
            val next = DesktopMonthsModel.apply(MonthsZoomController.monthsTap(clicked), cfg, today, dimensions)
            val days = daily(next, snap).days
            assertTrue("back=$back days=${days.map { it.date }}", days.any { it.date == clicked })
            val index = days.indexOfFirst { it.date == clicked }
            assertTrue("back=$back index=$index of ${days.size}", kotlin.math.abs(index - (days.size - 1) / 2) <= 1)
        }
    }

    @Test
    fun `suggests a visible source whose history reaches much further back, and only then`() {
        val depths = listOf(
            com.weatherwidget.shared.months.SourceDepth("GOOGLE_WEATHER", today.minusDays(12), 12),
            com.weatherwidget.shared.months.SourceDepth("NWS", today.minusDays(135), 135),
        )
        val base = snapshot()
        val snap = base.copy(raw = base.raw.copy(historyDepths = depths))
        val onGoogle = config.copy(settings = DesktopSettings(weatherSource = "GOOGLE_WEATHER", visibleSources = listOf("GOOGLE_WEATHER", "NWS")))
        val suggestion = DesktopMonthsModel.build(onGoogle, snap, today).moreHistory
        assertEquals("NWS", suggestion?.sourceId)
        assertEquals(
            "NWS has history back to Jun 2. Click to switch.",
            DesktopMonthsModel.moreHistoryText(suggestion!!, today),
        )
        val onNws = onGoogle.copy(settings = onGoogle.settings.copy(weatherSource = "NWS"))
        assertEquals(null, DesktopMonthsModel.build(onNws, snap, today).moreHistory)
        // Not a visible source: never suggested.
        val nwsHidden = onGoogle.copy(settings = onGoogle.settings.copy(visibleSources = listOf("GOOGLE_WEATHER")))
        assertEquals(null, DesktopMonthsModel.build(nwsHidden, snap, today).moreHistory)
    }

    @Test
    fun `pan is clamped to the data`() {
        val snap = snapshot()
        var cfg = config.copy(monthsView = true)
        var state = DesktopMonthsModel.build(cfg, snap, today)
        assertEquals(cfg, DesktopMonthsModel.pan(cfg, state, 30))
        repeat(40) {
            state = DesktopMonthsModel.build(cfg, snap, today)
            cfg = DesktopMonthsModel.pan(cfg, state, -DesktopMonthsModel.KEY_PAN_DAYS)
        }
        state = DesktopMonthsModel.build(cfg, snap, today)
        assertEquals(today.minusDays(200), state.window.start)
    }
}
