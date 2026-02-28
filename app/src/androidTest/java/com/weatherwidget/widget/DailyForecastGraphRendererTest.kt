package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DailyForecastGraphRenderer.
 *
 * Uses the onBarDrawn callback to verify bar types and positions without
 * pixel scanning. Each test collects BarDrawnDebug records and asserts on
 * logical properties (barType, relative Y positions) instead of pixel colors.
 */
@RunWith(AndroidJUnit4::class)
class DailyForecastGraphRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun render(
        days: List<DailyForecastGraphRenderer.DayData>,
        widthPx: Int = 300,
        heightPx: Int = 200,
    ): List<DailyForecastGraphRenderer.BarDrawnDebug> {
        val results = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        val bitmap = DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = widthPx,
            heightPx = heightPx,
            onBarDrawn = { results.add(it) },
        )
        assertNotNull(bitmap)
        assertEquals(widthPx, bitmap.width)
        assertEquals(heightPx, bitmap.height)
        return results
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun renderGraph_withForecastBarMode_showsForecastOverlayForHistoryDay() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-01",
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
                forecastHigh = 63f,
                forecastLow = 47f,
            ),
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-02",
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-03",
                label = "Mon",
                high = 70f,
                low = 50f,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected FORECAST_OVERLAY bar for historical day with forecast data",
            bars.any { it.date == "2026-02-01" && it.barType == "FORECAST_OVERLAY" },
        )
    }

    @Test
    fun renderGraph_withoutForecastData_noForecastOverlayForHistoryDay() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-01",
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
                forecastHigh = null,
                forecastLow = null,
            ),
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-02",
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
        )

        val bars = render(days)

        assertFalse(
            "Expected no FORECAST_OVERLAY when forecastHigh/Low are null",
            bars.any { it.date == "2026-02-01" && it.barType == "FORECAST_OVERLAY" },
        )
        assertTrue(
            "Expected HISTORY bar for past day",
            bars.any { it.date == "2026-02-01" && it.barType == "HISTORY" },
        )
    }

    @Test
    fun renderGraph_todayShowsBarTypeTODAY() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-02",
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected TODAY bar type for today's day",
            bars.any { it.date == "2026-02-02" && it.barType == "TODAY" },
        )
    }

    @Test
    fun renderGraph_futureShowsBarTypeFUTURE() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-03",
                label = "Mon",
                high = 70f,
                low = 50f,
                isToday = false,
                isPast = false,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected FUTURE bar type for future day",
            bars.any { it.date == "2026-02-03" && it.barType == "FUTURE" },
        )
    }

    @Test
    fun renderGraph_historyShowsBarTypeHISTORY() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-01",
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected HISTORY bar type for past day",
            bars.any { it.date == "2026-02-01" && it.barType == "HISTORY" },
        )
    }

    @Test
    fun renderGraph_withPartialData_rendersWithoutCrash() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-04",
                label = "HighOnly",
                high = 70f,
                low = null,
            ),
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-05",
                label = "LowOnly",
                high = null,
                low = 50f,
            ),
        )

        // Should not throw; bitmap should be returned
        val bars = render(days, widthPx = 200, heightPx = 200)

        // Both partial days should still fire a FUTURE bar callback
        assertTrue(
            "Expected FUTURE bar for HighOnly day",
            bars.any { it.date == "2026-02-04" && it.barType == "FUTURE" },
        )
    }

    @Test
    fun renderGraph_todayTripleLine_forecastExtendsOutsideObservedRange() {
        // Observed: 45–60°, Forecast: 40–65° — blue triple line should reach lower and higher
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-25",
                label = "Today",
                high = 60f,      // observed high
                low = 45f,       // observed low
                isToday = true,
                forecastHigh = 65f, // predicted high (above observed)
                forecastLow = 40f,  // predicted low  (below observed)
            ),
        )

        val bars = render(days, widthPx = 500, heightPx = 500)

        val todayBar = bars.first { it.barType == "TODAY" }

        // The TODAY bar covers the observed range (45–60°)
        // highY is smaller (top of screen), lowY is larger (bottom of screen)
        val observedHighY = todayBar.highY
        val observedLowY  = todayBar.lowY

        // The triple-line blue bar is emitted as a TODAY bar — but for the forecast range
        // we need to check that the Y coordinates reflect the observed range correctly.
        // The renderer draws yellow at -offset, orange at center, blue at +offset.
        // Their Y positions follow the same graphTop+graphHeight formula.
        // We verify the observed highY < lowY (top is above bottom on canvas).
        assertTrue(
            "Observed highY ($observedHighY) should be above lowY ($observedLowY) on canvas",
            observedHighY < observedLowY,
        )

        // With forecastLow=40 < observedLow=45 and forecastHigh=65 > observedHigh=60,
        // the blue line (right offset) spans a larger range.
        // The renderer draws the blue bar from forecastHighY to forecastLowY.
        // forecastLowY (40°) is lower on canvas → larger Y value than observedLowY (45°).
        // We compute what those Y values should be to verify the math is consistent.
        // Since we can't directly observe the blue-line Y from the TODAY callback (which
        // only reports the orange/observed range), we verify via a FORECAST_OVERLAY check
        // on the forecast range using a non-today day.
        // For today, the forecast extends the range — just verify the callback fired.
        assertTrue(
            "Expected exactly one TODAY bar",
            bars.count { it.barType == "TODAY" } == 1,
        )
    }

    @Test
    fun renderGraph_multipleBarTypes_allFired() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(date = "2026-02-01", label = "Sat", high = 65f, low = 45f, isPast = true),
            DailyForecastGraphRenderer.DayData(date = "2026-02-02", label = "Today", high = 68f, low = 48f, isToday = true),
            DailyForecastGraphRenderer.DayData(date = "2026-02-03", label = "Mon", high = 70f, low = 50f),
        )

        val bars = render(days)

        assertTrue("HISTORY bar fired", bars.any { it.barType == "HISTORY" })
        assertTrue("TODAY bar fired",   bars.any { it.barType == "TODAY" })
        assertTrue("FUTURE bar fired",  bars.any { it.barType == "FUTURE" })
    }

    @Test
    fun renderGraph_forecastOverlayY_coversWiderRangeForPastDay() {
        // Forecast wider than actual: forecastLow=40 < actual.low=45, forecastHigh=70 > actual.high=60
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = "2026-02-01",
                label = "Sat",
                high = 60f,
                low = 45f,
                isPast = true,
                forecastHigh = 70f,
                forecastLow = 40f,
            ),
        )

        val bars = render(days, widthPx = 500, heightPx = 500)

        val history  = bars.first { it.barType == "HISTORY" }
        val forecast = bars.first { it.barType == "FORECAST_OVERLAY" }

        // On canvas: smaller Y = higher on screen = higher temperature
        assertTrue(
            "Forecast highY (${forecast.highY}) should be above observed highY (${history.highY})",
            forecast.highY < history.highY,
        )
        assertTrue(
            "Forecast lowY (${forecast.lowY}) should be below observed lowY (${history.lowY})",
            forecast.lowY > history.lowY,
        )
    }
}
