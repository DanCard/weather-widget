package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyForecastGraphRendererRoboTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
    }

    private fun render(
        days: List<DailyForecastGraphRenderer.DayData>,
        widthPx: Int = 300,
        heightPx: Int = 200,
    ): List<DailyForecastGraphRenderer.BarDrawnDebug> {
        val results = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        val bitmap = runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = widthPx,
                heightPx = heightPx,
                onBarDrawn = { results.add(it) },
            )
        }
        assertNotNull(bitmap)
        return results
    }

    private fun renderRainLabels(
        days: List<DailyForecastGraphRenderer.DayData>,
        widthPx: Int = 300,
        heightPx: Int = 200,
        numColumns: Int = 0,
    ): List<DailyForecastGraphRenderer.RainLabelDrawnDebug> {
        val results = mutableListOf<DailyForecastGraphRenderer.RainLabelDrawnDebug>()
        val bitmap = runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = widthPx,
                heightPx = heightPx,
                numColumns = numColumns,
                onRainLabelDrawn = { results.add(it) },
            )
        }
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
                forecastHigh = 67f,
                forecastLow = 44f,
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
            "Expected TODAY bar for today",
            bars.any { it.date == feb02 && it.barType == "TODAY" },
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
            "Expected HISTORY bar for past day",
            bars.any { it.date == feb01 && it.barType == "HISTORY" },
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
            ),
        )

        val bars = render(days)

        assertTrue(
            "Expected FUTURE bar for future day",
            bars.any { it.date == feb03 && it.barType == "FUTURE" },
        )
    }

    @Test
    fun renderGraph_forecastOverlayY_coversWiderRangeForPastDay() {
        val pastDay = LocalDate.of(2026, 2, 1)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = pastDay,
                label = "Sat",
                high = 65f,
                low = 45f,
                isPast = true,
                forecastHigh = 70f,
                forecastLow = 40f,
            ),
        )

        val bars = render(days)

        val history = bars.single { it.barType == "HISTORY" }
        val forecast = bars.single { it.barType == "FORECAST_OVERLAY" }

        assertTrue(
            "Forecast overlay should start higher than history bar on graph (smaller Y)",
            forecast.highY < history.highY,
        )
        assertTrue(
            "Forecast overlay should end lower than history bar on graph (larger Y)",
            forecast.lowY > history.lowY,
        )
    }

    @Test
    fun renderGraph_placesRainLabelAboveHighWhenRoomExists() {
        // Use a dummy day with high=100 to push the graph scale up,
        // so the 70f day has more headroom below the header.
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 2),
                    label = "Sun",
                    high = 100f,
                    low = 80f,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
                ),
            ),
            widthPx = 800,
            heightPx = 500,
            numColumns = 4
        )

        assertEquals("Rain label should be shown when room exists", 1, labels.size)
        assertEquals("ABOVE_HIGH", labels.first().placement)
    }

    @Test
    fun renderGraph_drawsRainLabelInHeaderSpaceWhenHighTempIsNearTop() {
        // At 100px height, high temp at 100f is forced to graphTop (54px).
        // The rain label sits around 20-30px from top. With topMargin = graphTop * 0.2f (~11px),
        // the label fits in the header space above the graph area.
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 100f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
                ),
            ),
            widthPx = 800,
            heightPx = 100,
        )

        assertEquals("Rain label should be drawn in header space", 1, labels.size)
        assertEquals("65%", labels.first().text)
    }

    @Test
    fun renderGraph_placesRainLabelAboveHighWhenThereIsRoom() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 2),
                    label = "Sun",
                    high = 150f,
                    low = 32f,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
                ),
            ),
            widthPx = 500,
            heightPx = 500,
        )

        assertEquals(1, labels.size)
        assertEquals("ABOVE_HIGH", labels.first().placement)
        assertEquals("65%", labels.first().text)
        assertTrue(
            "Rain label should sit above the high-temp label without overlap. Label=${labels.first()}",
            labels.first().bottomY < labels.first().anchorTopY,
        )
    }

    @Test
    fun renderGraph_rainLabelDoesNotOverlapHighLabelInCompressedLayout() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 100f,
                    low = 72f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
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

        labels.forEach { label ->
            assertTrue(
                "Rain label should not overlap the high-temp label. Label=$label",
                label.bottomY < label.anchorTopY,
            )
        }
    }

    @Test
    fun renderGraph_rainLabelIsDrawnAboveHighWhenTopSpaceIsTight() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 100f,
                    low = 72f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
                ),
            ),
            widthPx = 500,
            heightPx = 100,
        )

        assertTrue(
            "Rain label should be drawn above high when header space is available. size=${labels.size}",
            labels.isNotEmpty()
        )
        assertEquals("ABOVE_HIGH", labels.first().placement)
    }

    @Test
    fun renderGraph_nightRainLabelDrawsBelowLowTemperature() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(nighttimePrecipProbability = 65, nightRainLabelText = "65%"),
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 4),
                    label = "Tue",
                    high = 65f,
                    low = 20f,
                ),
            ),
            widthPx = 800,
            heightPx = 500,
        )

        assertEquals(1, labels.size)
        assertEquals("NIGHT_INTERSTITIAL", labels.first().placement)
        assertEquals("65%", labels.first().text)
    }

    @Test
    fun renderGraph_nightRainLabelIsDrawnWhenDayRainLabelExists() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 2),
                    label = "Sun",
                    high = 150f,
                    low = 32f,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "30%", nighttimePrecipProbability = 65, nightRainLabelText = "65%"),
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 4),
                    label = "Tue",
                    high = 65f,
                    low = 45f,
                ),
            ),
            widthPx = 800,
            heightPx = 500,
        )

        assertEquals(2, labels.size)
        assertTrue(labels.any { it.placement == "ABOVE_HIGH" && it.text == "30%" })
        assertTrue(labels.any { it.placement == "NIGHT_INTERSTITIAL" && it.text == "65%" })
    }

    @Test
    fun renderGraph_nightRainLabelFallsBackWhenBaselinesEqual() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(nighttimePrecipProbability = 65, nightRainLabelText = "65%"),
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 4),
                    label = "Tue",
                    high = 65f,
                    low = 50f,
                ),
            ),
            widthPx = 800,
            heightPx = 500,
        )

        assertEquals(1, labels.size)
        assertEquals("NIGHT_SHIFTED_RIGHT", labels.first().placement)
    }

    @Test
    fun renderGraph_nightRainLabelInterstitialAnchorsBelowHigherLabel() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 30f,
                    rainData = DailyForecastGraphRenderer.RainData(nighttimePrecipProbability = 65, nightRainLabelText = "65%"),
                ),
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 4),
                    label = "Tue",
                    high = 65f,
                    low = 55f,
                ),
            ),
            widthPx = 800,
            heightPx = 500,
        )

        assertEquals(1, labels.size)
        val label = labels.first()
        assertEquals("NIGHT_INTERSTITIAL", label.placement)
        assertTrue("Interstitial baseline should be near the higher (lower-Y) temp label", label.baselineY < label.anchorBaselineY + 50f)
    }

    @Test
    fun renderGraph_nightRainLabelIsSkippedWhenTooWide() {
        val labels = renderRainLabels(
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.of(2026, 2, 3),
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    rainData = DailyForecastGraphRenderer.RainData(nighttimePrecipProbability = 100, nightRainLabelText = "100000000000000000000000000000000000000000000000000000%"),
                ),
            ),
            widthPx = 40,
            heightPx = 500,
        )

        assertTrue(labels.isEmpty())
    }

    @Test
    fun renderGraph_rainLabelScaling_doesNotMutateSharedPaint() {
        val today = LocalDate.now()
        val targetDay = today.plusDays(5)
        
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Today",
                high = 150f,
                low = 48f,
                isToday = true,
            ),
            DailyForecastGraphRenderer.DayData(
                date = targetDay,
                label = "Mon",
                high = 70f,
                low = 50f,
                rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "39%", dailyPrecipProbability = 39),
                daysFromToday = 5
            ),
        )

        val rendererClass = DailyForecastGraphRenderer::class.java
        val cachedPaintSetField = rendererClass.getDeclaredField("cachedPaintSet")
        cachedPaintSetField.isAccessible = true
        
        val width = 1000
        val height = 1000
        runBlocking {
            DailyForecastGraphRenderer.renderGraph(context, listOf(DailyForecastGraphRenderer.DayData(date = today, label = "X", high = 0f, low = 0f)), width, height)
        }
        val paintSet = cachedPaintSetField.get(null)
        assertNotNull(paintSet)
        
        val rainTextPaintField = paintSet!!.javaClass.getDeclaredField("rainTextPaint")
        rainTextPaintField.isAccessible = true
        val rainPaint = rainTextPaintField.get(paintSet) as Paint
        
        var sizeDuringFirstRender: Float = 0f
        var sizeDuringSecondRender: Float = 0f
        var firstRenderCallbackFired = false
        var secondRenderCallbackFired = false

        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = width,
                heightPx = height,
                onRainLabelDrawn = {
                    firstRenderCallbackFired = true
                    sizeDuringFirstRender = rainPaint.textSize
                    
                    runBlocking {
                        DailyForecastGraphRenderer.renderGraph(
                            context = context,
                            days = days,
                            widthPx = width,
                            heightPx = height,
                            onRainLabelDrawn = {
                                secondRenderCallbackFired = true
                                sizeDuringSecondRender = rainPaint.textSize
                            }
                        )
                    }
                }
            )
        }
        
        assertTrue("First render callback should have fired", firstRenderCallbackFired)
        assertTrue("Second render callback should have fired", secondRenderCallbackFired)
        assertEquals("Second render should have the SAME base text size as first", sizeDuringFirstRender, sizeDuringSecondRender, 0.01f)
    }

    @Test
    fun renderGraph_withPartialData_rendersWithoutCrash() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.now(),
                label = "Mon",
                high = null,
                low = null,
            ),
        )
        runBlocking {
            DailyForecastGraphRenderer.renderGraph(context, days, 500, 300)
        }
    }

    @Test
    fun renderGraph_historyMixedIcon_drawsActualRangeSolidRed() {
        val feb01 = LocalDate.of(2026, 2, 1)
        val day = DailyForecastGraphRenderer.DayData(
            date = feb01,
            label = "Sat",
            high = 65f,
            low = 45f,
            isPast = true,
            isMixed = true,
        )

        val bars = render(listOf(day))
        assertTrue(bars.any { it.barType == "HISTORY" })
    }

    @Test
    fun renderGraph_multipleBarTypes_allFired() {
        val feb01 = LocalDate.of(2026, 2, 1)
        val feb02 = LocalDate.of(2026, 2, 2)
        val feb03 = LocalDate.of(2026, 2, 3)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(date = feb01, label = "Sat", high = 60f, low = 40f, isPast = true),
            DailyForecastGraphRenderer.DayData(date = feb02, label = "Today", high = 65f, low = 45f, isToday = true),
            DailyForecastGraphRenderer.DayData(date = feb03, label = "Mon", high = 70f, low = 50f),
        )

        val bars = render(days)
        assertTrue(bars.any { it.barType == "HISTORY" })
        assertTrue(bars.any { it.barType == "TODAY" })
        assertTrue(bars.any { it.barType == "FUTURE" })
    }
}
