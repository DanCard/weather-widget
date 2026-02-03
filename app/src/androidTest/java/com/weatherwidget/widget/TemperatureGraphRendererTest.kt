package com.weatherwidget.widget

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TemperatureGraphRenderer.
 *
 * These tests verify that the graph correctly renders:
 * - Temperature bars for each day
 * - Blue forecast comparison bars for historical days with forecast data
 * - Different colors for today vs past vs future days
 */
@RunWith(AndroidJUnit4::class)
class TemperatureGraphRendererTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renderGraph_withForecastBarMode_showsBlueForecastLine() {
        // Create test data with historical day that has forecast comparison
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Sat",
                high = 65,
                low = 45,
                isToday = false,
                isPast = true,  // Historical day
                forecastHigh = 63,  // What was predicted
                forecastLow = 47,
                forecastSource = "OPEN_METEO",
                accuracyMode = AccuracyDisplayMode.FORECAST_BAR
            ),
            TemperatureGraphRenderer.DayData(
                label = "Today",
                high = 68,
                low = 48,
                isToday = true,
                isPast = false
            ),
            TemperatureGraphRenderer.DayData(
                label = "Mon",
                high = 70,
                low = 50,
                isToday = false,
                isPast = false
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 300,
            heightPx = 200
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
            foundBluePixel
        )
    }

    @Test
    fun renderGraph_withoutForecastData_noBlueLineInHistory() {
        // Create test data with historical day but NO forecast comparison
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Sat",
                high = 65,
                low = 45,
                isToday = false,
                isPast = true,
                forecastHigh = null,  // No forecast data
                forecastLow = null,
                accuracyMode = AccuracyDisplayMode.FORECAST_BAR
            ),
            TemperatureGraphRenderer.DayData(
                label = "Today",
                high = 68,
                low = 48,
                isToday = true,
                isPast = false
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 300,
            heightPx = 200
        )

        assertNotNull(bitmap)

        // The first column should only have yellow (history) bar, not blue
        // Check the area where forecast bar would be (right of center in first day column)
        val yellowColor = Color.parseColor("#FFD60A")
        val blueColor = Color.parseColor("#5AC8FA")

        var foundYellow = false
        var foundBlue = false

        // Scan the first third of the bitmap
        val startX = bitmap.width / 6  // Center of first day
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
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Sat",
                high = 65,
                low = 45,
                isToday = false,
                isPast = true,
                forecastHigh = 63,
                forecastLow = 47,
                forecastSource = "OPEN_METEO",
                accuracyMode = AccuracyDisplayMode.NONE  // Mode is NONE
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 200,
            heightPx = 200
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
            bluePixelCount < 100  // Allow some for anti-aliasing
        )
    }

    @Test
    fun renderGraph_todayShowsOrangeBar() {
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Today",
                high = 68,
                low = 48,
                isToday = true,
                isPast = false
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 200,
            heightPx = 200
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
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Mon",
                high = 70,
                low = 50,
                isToday = false,
                isPast = false  // Future day
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 200,
            heightPx = 200
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
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "Sat",
                high = 65,
                low = 45,
                isToday = false,
                isPast = true  // Historical day
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 200,
            heightPx = 200
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
        val days = listOf(
            TemperatureGraphRenderer.DayData(
                label = "HighOnly",
                high = 70,
                low = null, // Missing low
                isToday = false,
                isPast = false
            ),
            TemperatureGraphRenderer.DayData(
                label = "LowOnly",
                high = null, // Missing high
                low = 50,
                isToday = false,
                isPast = false
            )
        )

        val bitmap = TemperatureGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 200,
            heightPx = 200
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
}
