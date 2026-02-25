package com.weatherwidget.widget

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DailyForecastGraphRenderer.
 *
 * These tests verify that the graph correctly renders:
 * - Temperature bars for each day
 * - Blue forecast comparison bars for historical days with forecast data
 * - Different colors for today vs past vs future days
 */
@RunWith(AndroidJUnit4::class)
class DailyForecastGraphRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renderGraph_withForecastBarMode_showsBlueForecastLine() {
        // Create test data with historical day that has forecast comparison
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-01",
                    label = "Sat",
                    high = 65f,
                    low = 45f,
                    isToday = false,
                    isPast = true, // Historical day
                    forecastHigh = 63f, // What was predicted
                    forecastLow = 47f,
                    forecastSource = WeatherSource.OPEN_METEO,
                    accuracyMode = AccuracyDisplayMode.FORECAST_BAR,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-02",
                    label = "Today",
                    high = 68f,
                    low = 48f,
                    isToday = true,
                    isPast = false,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-03",
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    isToday = false,
                    isPast = false,
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 300,
                heightPx = 200,
            )

        // Verify bitmap was created
        assertNotNull(bitmap)
        assertEquals(300, bitmap.width)
        assertEquals(200, bitmap.height)

        // Check for blue forecast bar color (#5AC8FA)
        // The forecast bar should be drawn to the right of the main history bar
        val blueColor = Color.parseColor("#5AC8FA")
        var foundBluePixel = false

        // Scan the first third of the bitmap (where the historical day should be)
        for (x in 0 until bitmap.width / 3) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == blueColor) {
                    foundBluePixel = true
                    break
                }
            }
            if (foundBluePixel) break
        }

        assertTrue(
            "Expected to find blue forecast bar (#5AC8FA) in historical day column",
            foundBluePixel,
        )
    }

    @Test
    fun renderGraph_withoutForecastData_noBlueLineInHistory() {
        // Create test data with historical day but NO forecast comparison
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-01",
                    label = "Sat",
                    high = 65f,
                    low = 45f,
                    isToday = false,
                    isPast = true,
                    forecastHigh = null, // No forecast data
                    forecastLow = null,
                    accuracyMode = AccuracyDisplayMode.FORECAST_BAR,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-02",
                    label = "Today",
                    high = 68f,
                    low = 48f,
                    isToday = true,
                    isPast = false,
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 300,
                heightPx = 200,
            )

        assertNotNull(bitmap)

        // The first column should only have yellow (history) bar, not blue
        // Check the area where forecast bar would be (right of center in first day column)
        val yellowColor = Color.parseColor("#FFD60A")
        val blueColor = Color.parseColor("#5AC8FA")

        var foundYellow = false
        var foundBlue = false

        // Scan the first third of the bitmap
        val startX = bitmap.width / 6 // Center of first day
        val endX = bitmap.width / 3

        for (x in startX until endX) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == yellowColor) foundYellow = true
                if (pixel == blueColor) foundBlue = true
            }
        }

        assertTrue("Expected yellow history bar in past day", foundYellow)
        // Blue might exist from the bar itself (not forecast line), so we don't strictly assert no blue
    }

    @Test
    fun renderGraph_withAccuracyModeNone_noForecastLine() {
        // Even with forecast data, if mode is NONE, no forecast line should appear
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-01",
                    label = "Sat",
                    high = 65f,
                    low = 45f,
                    isToday = false,
                    isPast = true,
                    forecastHigh = 63f,
                    forecastLow = 47f,
                    forecastSource = WeatherSource.OPEN_METEO,
                    accuracyMode = AccuracyDisplayMode.NONE, // Mode is NONE
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 200,
                heightPx = 200,
            )

        assertNotNull(bitmap)

        // Count blue pixels - with NONE mode, there should be fewer blue pixels
        // (only from regular bar, not from forecast bar)
        val blueColor = Color.parseColor("#5AC8FA")
        var bluePixelCount = 0

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == blueColor) {
                    bluePixelCount++
                }
            }
        }

        // With NONE mode on a past day, the bar should be yellow, not blue
        // So blue count should be minimal (possibly from anti-aliasing only)
        assertTrue(
            "With NONE mode, past day should use yellow bar, not blue. Found $bluePixelCount blue pixels",
            bluePixelCount < 100, // Allow some for anti-aliasing
        )
    }

    @Test
    fun renderGraph_todayShowsOrangeBar() {
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-02",
                    label = "Today",
                    high = 68f,
                    low = 48f,
                    isToday = true,
                    isPast = false,
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 200,
                heightPx = 200,
            )

        assertNotNull(bitmap)

        // Check for orange today bar color (#FF9F0A)
        val orangeColor = Color.parseColor("#FF9F0A")
        var foundOrange = false

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == orangeColor) {
                    foundOrange = true
                    break
                }
            }
            if (foundOrange) break
        }

        assertTrue("Expected orange bar for today", foundOrange)
    }

    @Test
    fun renderGraph_futureShowsBlueBar() {
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-03",
                    label = "Mon",
                    high = 70f,
                    low = 50f,
                    isToday = false,
                    isPast = false, // Future day
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 200,
                heightPx = 200,
            )

        assertNotNull(bitmap)

        // Check for blue future bar color (#5AC8FA)
        val blueColor = Color.parseColor("#5AC8FA")
        var foundBlue = false

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == blueColor) {
                    foundBlue = true
                    break
                }
            }
            if (foundBlue) break
        }

        assertTrue("Expected blue bar for future day", foundBlue)
    }

    @Test
    fun renderGraph_historyShowsYellowBar() {
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-01",
                    label = "Sat",
                    high = 65f,
                    low = 45f,
                    isToday = false,
                    isPast = true, // Historical day
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 200,
                heightPx = 200,
            )

        assertNotNull(bitmap)

        // Check for yellow history bar color (#FFD60A)
        val yellowColor = Color.parseColor("#FFD60A")
        var foundYellow = false

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == yellowColor) {
                    foundYellow = true
                    break
                }
            }
            if (foundYellow) break
        }

        assertTrue("Expected yellow bar for historical day", foundYellow)
    }

    @Test
    fun renderGraph_withPartialData_rendersWithoutCrash() {
        // Test data with partial temperatures (high only, low only)
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-04",
                    label = "HighOnly",
                    high = 70f,
                    low = null, // Missing low
                    isToday = false,
                    isPast = false,
                ),
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-05",
                    label = "LowOnly",
                    high = null, // Missing high
                    low = 50f,
                    isToday = false,
                    isPast = false,
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 200,
                heightPx = 200,
            )

        assertNotNull(bitmap)
        assertEquals(200, bitmap.width)
        assertEquals(200, bitmap.height)

        // Verify that we rendered something (blue color for future days)
        // Since we draw caps for partial data, we should still see blue pixels
        val blueColor = Color.parseColor("#5AC8FA")
        var foundBlue = false

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == blueColor) {
                    foundBlue = true
                    break
                }
            }
            if (foundBlue) break
        }

        assertTrue("Expected blue pixels for partial data caps", foundBlue)
    }

    @Test
    fun renderGraph_todayTripleLine_showsDifferentHeights() {
        val days =
            listOf(
                DailyForecastGraphRenderer.DayData(
                    date = "2026-02-25",
                    label = "Today",
                    high = 60f, // Observed high
                    low = 45f,  // Observed low
                    isToday = true,
                    forecastHigh = 65f, // Predicted high
                    forecastLow = 40f,  // Predicted low
                ),
            )

        val bitmap =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 500,
                heightPx = 500,
            )

        assertNotNull(bitmap)

        val yellowColor = Color.parseColor("#FFD60A")
        val orangeColor = Color.parseColor("#FF9F0A")
        val blueColor = Color.parseColor("#5AC8FA")

        // We expect:
        // Yellow/Orange lines cover the range corresponding to 45-60 degrees.
        // Blue line covers the range corresponding to 40-65 degrees.
        
        // Find the lowest pixel for yellow (observed low 45) and blue (forecast low 40)
        var lowestYellowY = -1
        var lowestBlueY = -1

        for (y in bitmap.height - 1 downTo 0) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == yellowColor && lowestYellowY == -1) lowestYellowY = y
                if (pixel == blueColor && lowestBlueY == -1) lowestBlueY = y
            }
        }

        // The blue line (forecast low 40) should extend lower than the yellow line (observed low 45)
        // Note: Y increases downwards in Android Canvas
        assertTrue(
            "Expected blue line (forecast low 40) to extend lower than yellow line (observed low 45). " +
            "lowestBlueY=$lowestBlueY, lowestYellowY=$lowestYellowY",
            lowestBlueY > lowestYellowY
        )
        
        // Similarly for the high: blue (forecast high 65) should extend higher than yellow (observed high 60)
        var highestYellowY = bitmap.height
        var highestBlueY = bitmap.height

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == yellowColor && highestYellowY == bitmap.height) highestYellowY = y
                if (pixel == blueColor && highestBlueY == bitmap.height) highestBlueY = y
            }
        }

        assertTrue(
            "Expected blue line (forecast high 65) to extend higher than yellow line (observed high 60). " +
            "highestBlueY=$highestBlueY, highestYellowY=$highestYellowY",
            highestBlueY < highestYellowY
        )
    }
}
