package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyForecastGraphRendererRoboTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

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
        return results
    }

    private fun renderRainLabels(
        days: List<DailyForecastGraphRenderer.DayData>,
        widthPx: Int = 300,
        heightPx: Int = 200,
    ): List<DailyForecastGraphRenderer.RainLabelDrawnDebug> {
        val results = mutableListOf<DailyForecastGraphRenderer.RainLabelDrawnDebug>()
        val bitmap = DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = widthPx,
            heightPx = heightPx,
            onRainLabelDrawn = { results.add(it) },
        )
        assertNotNull(bitmap)
        return results
    }

    @Test
    fun renderGraph_withForecastBarMode_showsForecastOverlayForHistoryDay() {
        val feb01 = LocalDate.of(2026, 2, 1)
        val feb02 = LocalDate.of(2026, 2, 2)
        val feb03 = LocalDate.of(2026, 2, 3)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb01,
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
                forecastHigh = 63f,
                forecastLow = 47f,
            ),
            DailyForecastGraphRenderer.DayData(
                date = feb02,
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
            DailyForecastGraphRenderer.DayData(
                date = feb03,
                label = "Mon",
                high = 70f,
                low = 50f,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected FORECAST_OVERLAY bar for historical day with forecast data",
            bars.any { it.date == feb01 && it.barType == "FORECAST_OVERLAY" },
        )
    }

    @Test
    fun renderGraph_withoutForecastData_noForecastOverlayForHistoryDay() {
        val feb01 = LocalDate.of(2026, 2, 1)
        val feb02 = LocalDate.of(2026, 2, 2)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb01,
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
                forecastHigh = null,
                forecastLow = null,
            ),
            DailyForecastGraphRenderer.DayData(
                date = feb02,
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
        )

        val bars = render(days)

        assertFalse(
            "Expected no FORECAST_OVERLAY when forecastHigh/Low are null",
            bars.any { it.date == feb01 && it.barType == "FORECAST_OVERLAY" },
        )
        assertTrue(
            "Expected HISTORY bar for past day",
            bars.any { it.date == feb01 && it.barType == "HISTORY" },
        )
    }

    @Test
    fun renderGraph_todayShowsBarTypeTODAY() {
        val feb02 = LocalDate.of(2026, 2, 2)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb02,
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected TODAY bar type for today's day",
            bars.any { it.date == feb02 && it.barType == "TODAY" },
        )
    }

    @Test
    fun renderGraph_futureShowsBarTypeFUTURE() {
        val feb03 = LocalDate.of(2026, 2, 3)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb03,
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
            bars.any { it.date == feb03 && it.barType == "FUTURE" },
        )
    }

    @Test
    fun renderGraph_historyShowsBarTypeHISTORY() {
        val feb01 = LocalDate.of(2026, 2, 1)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb01,
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected HISTORY bar type for past day",
            bars.any { it.date == feb01 && it.barType == "HISTORY" },
        )
    }

    @Test
    fun renderGraph_withPartialData_rendersWithoutCrash() {
        val feb04 = LocalDate.of(2026, 2, 4)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = feb04,
                label = "HighOnly",
                high = 70f,
                low = null,
            ),
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 2, 5),
                label = "LowOnly",
                high = null,
                low = 50f,
            ),
        )

        val bars = render(days, widthPx = 200, heightPx = 200)

        assertTrue(
            "Expected FUTURE bar for HighOnly day",
            bars.any { it.date == feb04 && it.barType == "FUTURE" },
        )
    }

    @Test
    fun renderGraph_multipleBarTypes_allFired() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(date = LocalDate.of(2026, 2, 1), label = "Sat", high = 65f, low = 45f, isPast = true),
            DailyForecastGraphRenderer.DayData(date = LocalDate.of(2026, 2, 2), label = "Today", high = 68f, low = 48f, isToday = true),
            DailyForecastGraphRenderer.DayData(date = LocalDate.of(2026, 2, 3), label = "Mon", high = 70f, low = 50f),
        )

        val bars = render(days)

        assertTrue("HISTORY bar fired", bars.any { it.barType == "HISTORY" })
        assertTrue("TODAY bar fired", bars.any { it.barType == "TODAY" })
        assertTrue("FUTURE bar fired", bars.any { it.barType == "FUTURE" })
    }

    @Test
    fun renderGraph_forecastOverlayY_coversWiderRangeForPastDay() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 2, 1),
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

        assertTrue(
            "Forecast highY (${forecast.highY}) should be above observed highY (${history.highY})",
            forecast.highY < history.highY,
        )
        assertTrue(
            "Forecast lowY (${forecast.lowY}) should be below observed lowY (${history.lowY})",
            forecast.lowY > history.lowY,
        )
    }

    @Test
    fun renderGraph_placesRainLabelAboveHighWhenThereIsRoom() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 2),
                    label = "Sun",
                    high = 88f,
                    low = 32f,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    dailyRainLabelText = "65%",
                ),
            ),
            widthPx = 500,
            heightPx = 500,
        )

        assertEquals(1, labels.size)
        assertEquals("ABOVE_HIGH", labels.first().placement)
        assertEquals("65%", labels.first().text)
    }

    @Test
    fun renderGraph_rainLabelMayBeOmittedWhenSpaceIsTight() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 100f,
                    low = 72f,
                    dailyRainLabelText = "65%",
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 4),
                    label = "Tue",
                    high = 78f,
                    low = 22f,
                ),
            ),
            widthPx = 500,
            heightPx = 100,
        )

        assertTrue(
            "Rain label at very small height should be 0 or 1 (layout-dependent), got ${labels.size}",
            labels.size <= 1,
        )
    }

    @Test
    fun renderGraph_rainLabelScaling_doesNotMutateSharedPaint() {
        val feb03 = LocalDate.of(2026, 2, 3)
        // Day 5 days from "today" (if we don't specify now, it uses current date, but let's just use enough days)
        val today = LocalDate.now()
        val targetDay = today.plusDays(5)
        
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Today",
                high = 68f,
                low = 48f,
                isToday = true,
            ),
            DailyForecastGraphRenderer.DayData(
                date = targetDay,
                label = "Mon",
                high = 70f,
                low = 50f,
                dailyRainLabelText = "39%",
                dailyPrecipProbability = 39,
                daysFromToday = 5
            ),
        )

        // First render to initialize and (currently) mutate the shared paint
        renderRainLabels(days)
        
        // Use reflection to check the private cachedPaintSet in DailyForecastGraphRenderer
        val rendererClass = DailyForecastGraphRenderer::class.java
        val cachedPaintSetField = rendererClass.getDeclaredField("cachedPaintSet")
        cachedPaintSetField.isAccessible = true
        val paintSet = cachedPaintSetField.get(null)
        assertNotNull("cachedPaintSet should be initialized after render", paintSet)
        
        val rainTextPaintField = paintSet!!.javaClass.getDeclaredField("rainTextPaint")
        rainTextPaintField.isAccessible = true
        val rainPaint = rainTextPaintField.get(paintSet) as android.graphics.Paint
        
        val initialSize = rainPaint.textSize
        
        // Second render - if the bug exists, this will mutate it FURTHER or we can check if it already mutated
        renderRainLabels(days)
        
        assertEquals(
            "Rain text size should remain constant across renders (no shared state mutation)",
            initialSize,
            rainPaint.textSize,
            0.001f
        )
    }
}