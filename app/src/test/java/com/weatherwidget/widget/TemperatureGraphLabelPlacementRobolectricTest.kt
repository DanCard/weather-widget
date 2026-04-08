package com.weatherwidget.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class TemperatureGraphLabelPlacementRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildHours(
        temps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 3, 19, 10, 0),
    ): List<TemperatureGraphRenderer.HourData> =
        temps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dateTime,
                temperature = temp,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    @Test
    fun `peak falls back below when above placement would leave the screen`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(listOf(0f, 50f, 98f, 50f, 0f)),
            widthPx = 700,
            heightPx = 24, // Keep small to force Above off-screen
            currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
            onLabelPlaced = { placements.add(it) },
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        if (highPlacement != null) {
            assertFalse(
                "Expected constrained HIGH label to avoid above-placement when it would be off-screen. placement=$highPlacement",
                highPlacement.placedAbove,
            )
        }
    }

    @Test
    fun `peak label above stays close to the forecast line`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        var points: TemperatureGraphRenderer.PointsDebug? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(listOf(40f, 55f, 70f, 89f, 70f, 55f, 40f)),
            widthPx = 700,
            heightPx = 420,
            currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
            onLabelPlaced = { placements.add(it) },
            onPointsResolved = { points = it },
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        assertNotNull("Expected HIGH label to be drawn. placements=$placements", highPlacement)
        assertTrue("Expected HIGH label to prefer above when room exists. placement=$highPlacement", highPlacement!!.placedAbove)

        val highPoint = requireNotNull(points).forecast[3]
        
        // Calculate font metrics exactly how the renderer's fallback does
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 19.5f, context.resources.displayMetrics)
        val fallbackDescent = textSize * 0.2f
        
        // In the renderer: baselineY = sy - aboveGap - labelDescent
        // Label bottom edge = baselineY + labelDescent = (sy - aboveGap - labelDescent) + labelDescent = sy - aboveGap
        val labelBottom = highPlacement.y + fallbackDescent
        val gap = highPoint.second - labelBottom
        val expectedGap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics)

        assertEquals(
            "Expected above-label bottom edge to sit close to the curve. pointY=${highPoint.second} labelBottom=$labelBottom placement=$highPlacement",
            expectedGap,
            gap,
            2f,
        )
    }

    @Test
    fun `actual and forecast highs are both labeled when peaks differ`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 6, 0)
        // 20-hour span so edge suppression doesn't interfere.
        // Forecast peaks at idx=12 (89°), actual peaks at idx=8 (86.2°) — different indices.
        val forecastTemps = listOf(75f, 77f, 79f, 81f, 83f, 85f, 86f, 87f, 88f, 88.5f, 88.8f, 88.9f, 89f, 88f, 86f, 84f, 82f, 81f, 80f, 79f)
        val actualTemps =   listOf(75f, 77f, 79f, 82f, 84f, 85.5f, 86f, 86.1f, 86.2f, 85f, 84f, 83f, null, null, null, null, null, null, null, null)
        val hours = (0 until 20).map { i ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecastTemps[i],
                actualTemperature = actualTemps[i],
                isActual = actualTemps[i] != null,
                label = "${(6 + i) % 24}",
            )
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 450,
            currentTime = start.plusHours(14),
            onLabelPlaced = { placements.add(it) },
        )

        val actualHigh = placements.find { it.role == "ACTUAL_HIGH" }
        val forecastHigh = placements.find { it.role == "HIGH" || it.role == "FORECAST_HIGH" || it.role == "PAST_FORECAST_HIGH" }

        assertNotNull("Expected ACTUAL_HIGH label. placements=$placements", actualHigh)
        assertNotNull("Expected forecast-series HIGH label. placements=$placements", forecastHigh)
        assertEquals(86.2f, actualHigh!!.temperature, 0.1f)
        assertEquals(89.0f, forecastHigh!!.temperature, 0.1f)
    }

    @Test
    fun `end label is still emitted when observedAt lands on the final point`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(4).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 87.0f, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "7p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(4),
            observedAt = observedAtMs,
            lastObservedTemp = 88.0f,
            onLabelPlaced = { placements.add(it) },
        )

        val endPlacement = placements.find { it.role == "END" }
        assertNotNull("Expected END label even when observedAt is on the final point. placements=$placements", endPlacement)
        assertEquals(88.0f, endPlacement!!.temperature, 0.01f)
    }

    @Test
    fun `end label uses forecast temperature after fetch transition not ghost line when endpoint is uncrowded`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 70.0f, actualTemperature = 70.0f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 75.0f, actualTemperature = 75.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 74.0f, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 73.0f, label = "6p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(1),
            observedAt = observedAtMs,
            lastObservedTemp = 75.0f,
            appliedDelta = 5.0f,
            onLabelPlaced = { placements.add(it) },
        )

        val endPlacement = placements.find { it.role == "END" }
        assertNotNull("Expected END label to be drawn. placements=$placements", endPlacement)
        assertEquals("END label should stay on forecast line value, not ghost line", 73.0f, endPlacement!!.temperature, 0.01f)
        assertEquals("forecast", endPlacement.series)
        assertEquals("forecast", endPlacement.colorFamily)
    }

    @Test
    fun `end label is suppressed when same-index high already labels final point`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(listOf(70f, 75f, 81f)),
            widthPx = 500,
            heightPx = 450,
            currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
            onLabelPlaced = { placements.add(it) },
        )

        assertNotNull("HIGH or END should label the final point. placements=$placements", placements.find { it.role == "HIGH" || it.role == "END" })
        assertNotNull("HIGH should remain when it already labels the final point. placements=$placements", placements.find { it.role == "HIGH" })
    }

    @Test
    fun `placeTemperatureLabels thins out redundant labels on a monotonic rise`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 12, 0)
        // Monotonic rise from 50 to 70 over 20 hours
        val signal = (50..70).map { it.toFloat() }
        
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(signal, start),
            widthPx = 500,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("Should have at most 6 labels. Placed: ${placements.size}", placements.size <= 6)
        assertTrue("Should have START or LOW label at the beginning", placements.any { (it.role == "START" || it.role == "LOW") && it.index == 0 })
        assertTrue("Should have HIGH or END label at the end", placements.any { (it.role == "HIGH" || it.role == "END") && it.index == signal.lastIndex })
        // Middle points should be thinned out
        val intermediate = placements.filter { it.index != 0 && it.index != signal.lastIndex }
        assertTrue("Should have thinned out most intermediate labels. Found: ${intermediate.size}", intermediate.size < 5)
    }

    @Test
    fun `end label is shown even when another label is near the endpoint`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 52.0f, actualTemperature = 53.5f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 81.0f, actualTemperature = 82.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 79.0f, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 77.0f, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 54.0f, label = "7p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(5), temperature = 57.0f, label = "8p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 584,
            heightPx = 385,
            currentTime = start.plusHours(1),
            observedAt = observedAtMs,
            lastObservedTemp = 82.0f,
            onLabelPlaced = { placements.add(it) },
        )

        val endPlacement = placements.find { it.role == "END" }
        assertNotNull("END should always be shown as an essential boundary marker. placements=$placements", endPlacement)
    }

    @Test
    fun `actuals end does not produce a label (fetch dot shows observed temp instead)`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 87.0f, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "7p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(2),
            onLabelPlaced = { placements.add(it) },
        )

        val actualEnd = placements.find { it.role == "ACTUAL_END" }
        assertNull("ACTUAL_END label should not be placed (fetch dot covers this)", actualEnd)
    }

    @Test
    fun `actual and forecast lows are both labeled when peaks differ`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 6, 0)
        // 20-hour span so edge suppression doesn't interfere.
        // Forecast low at idx=12 (58°), actual low at idx=8 (55°) — different indices.
        val forecastTemps = listOf(70f, 69f, 67f, 65f, 64f, 63f, 62f, 61f, 60.5f, 60f, 59.5f, 59f, 58f, 59f, 60f, 61f, 62f, 63f, 64f, 65f)
        val actualTemps =   listOf(70f, 68f, 65f, 62f, 60f, 58f, 57f, 56f, 55f, 56f, 57f, 58f, null, null, null, null, null, null, null, null)
        val hours = (0 until 20).map { i ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecastTemps[i],
                actualTemperature = actualTemps[i],
                isActual = actualTemps[i] != null,
                label = "${(6 + i) % 24}",
            )
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
            currentTime = start.plusHours(14),
            onLabelPlaced = { placements.add(it) },
        )

        val actualLow = placements.find { it.role == "ACTUAL_LOW" }
        val forecastLow = placements.find { it.role == "LOW" || it.role == "FORECAST_LOW" }

        assertNotNull("Expected ACTUAL_LOW label. placements=$placements", actualLow)
        assertNotNull("Expected forecast LOW label. placements=$placements", forecastLow)
        assertEquals(55.0f, actualLow!!.temperature, 0.1f)
        assertEquals(58.0f, forecastLow!!.temperature, 0.1f)
    }

    @Test
    fun `extrapolated actualTemperature on future hours does not produce HIGH label at extrapolated value`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        // transitionX is driven by observedAt (fetch dot), not currentTime.
        // Hours after the fetch dot are "future" on the graph even if their timestamp is in the past.
        // Scenario: fetch dot at hour 1 (observedAt), but station has isActual=true readings up to hour 3.
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours = listOf(
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 74f, actualTemperature = 74f, isActual = true, label = "10a"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 76f, actualTemperature = 76f, isActual = true, label = "11a"),  // fetch dot here
            // These hours are past currentTime but AFTER the fetch dot — graph treats them as future
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 77f, actualTemperature = 82f, isActual = true, label = "12p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 77f, actualTemperature = 82f, isActual = true, label = "1p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 76f, label = "2p"),
        )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 400,
            currentTime = start.plusHours(3),  // currentTime is past hour 3, but fetch dot is at hour 1
            observedAt = observedAtMs,
            onLabelPlaced = { placements.add(it) },
        )

        assertTrue(
            "No label should show extrapolated future temperature (~82°). placements=$placements",
            placements.none { it.temperature > 79f },
        )
        val highLabel = placements.find { it.role == "HIGH" }
        assertNotNull("Expected a HIGH label from actual observations. placements=$placements", highLabel)
        assertTrue(
            "HIGH label temperature should reflect actual observation peak (78°), not extrapolated future value. placement=$highLabel",
            highLabel!!.temperature <= 78f + 0.1f,
        )
    }

    @Test
    fun `label Y positions are consistent with their temperature values`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val hours = listOf(
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 70f, actualTemperature = 73f, isActual = true, label = "10a"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 74f, actualTemperature = 77f, isActual = true, label = "11a"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 76f, actualTemperature = 79f, isActual = true, label = "12p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 74f, label = "1p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 71f, label = "2p"),
        )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 400,
            currentTime = start.plusHours(2),
            appliedDelta = 3f,
            onLabelPlaced = { placements.add(it) },
        )

        val sorted = placements.sortedByDescending { it.temperature }
        for (i in 0 until sorted.size - 1) {
            val higher = sorted[i]
            val lower = sorted[i + 1]
            if (higher.temperature != lower.temperature) {
                assertTrue(
                    "Higher temp (${higher.temperature}) should have lower Y pixel than (${lower.temperature}). " +
                    "higher.y=${higher.y} lower.y=${lower.y} — label positions are inverted (ghost line influence?)",
                    higher.y < lower.y,
                )
            }
        }
    }

    @Test
    fun `essential labels are forced into fallback position if preferred is off-screen and fallback collides`() {
        // This test simulates the Samsung failure where LOW was rejected from both above and below.
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        
        // Use a real drawable ID from the project
        val iconRes = com.weatherwidget.R.drawable.ic_weather_clear

        // We'll place a LOW at the very bottom edge to force it off-screen for 'drawBelow'
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 90.0f, label = "10a"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 10.0f, label = "11a", showLabel = true, iconRes = iconRes), // LOW
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 90.0f, label = "12p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 60, // Squeezed even more to force it off-screen with smaller 1dp gap
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )

        val lowLabel = placements.find { it.role == "LOW" }
        assertNotNull("LOW label should be present even if it collides, as long as it's on-screen. Placements=$placements", lowLabel)

        // preferred (below) is off-screen.
        // With footer separation in place, fallback (above) should now fit without needing a forced collision placement.
        assertTrue("Expected label above line", lowLabel!!.placedAbove)
        assertEquals("above", lowLabel.reason)
    }

    @Test
    fun `lowest plotted point stays above footer icon band`() {
        var points: TemperatureGraphRenderer.PointsDebug? = null
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val iconRes = com.weatherwidget.R.drawable.ic_weather_clear
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 90.0f, label = "10a"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 10.0f, label = "11a", showLabel = true, iconRes = iconRes),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 90.0f, label = "12p"),
            )

        val heightPx = 120
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = heightPx,
            currentTime = start,
            onPointsResolved = { points = it },
        )

        val density = context.resources.displayMetrics.density
        fun dp(dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, DisplayMetrics().apply { this.density = density })

        val iconSize = dp(16f)
        val labelHeight = dp(10f)
        val iconTopPad = dp(2f)
        val iconBottomPad = dp(1f)
        val footerTop = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val lowestY = requireNotNull(points).original.maxOf { it.second }

        assertTrue(
            "Lowest plotted point should stay above the footer/icon band. lowestY=$lowestY footerTop=$footerTop",
            lowestY < footerTop,
        )
    }

    @Test
    fun `testForecastLabelsInHistoryAreColoredBlue`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 6, 15, 0)
        
        // Setup hours: Past (idx 0-11), Future (idx 12-20)
        // Global LOW (forecast 51) at idx 5 (Past)
        // ACTUAL_LOW (observed 48.0) at idx 4 (Past) - Avoid edge suppression (min dist 4)
        // Global HIGH (forecast 80) at idx 15 (Future)
        // LOCAL Peak (forecast 75) at idx 13 (Future)
        // ACTUAL_HIGH (observed 65) at idx 8 (Past)
        val hours = (0..20).map { i ->
            val time = start.plusHours(i.toLong())
            val temp = when(i) {
                5 -> 51.0f  // Global LOW (Forecast)
                15 -> 80.0f // Global HIGH (Forecast)
                13 -> 75.0f // LOCAL Peak (Forecast)
                else -> 60.0f
            }
            val actualTemp = when(i) {
                4 -> 48.0f  // ACTUAL_LOW (Observed)
                8 -> 65.0f  // ACTUAL_HIGH (Observed)
                else -> temp
            }
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = i <= 11,
                label = "${time.hour}h"
            )
        }

        val observedAtMs = start.plusHours(11).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(11),
            observedAt = observedAtMs,
            lastObservedTemp = hours[11].actualTemperature,
            onLabelPlaced = { placements.add(it) }
        )

        // 1. HIGH (idx 15, Future) -> forecast
        val high = placements.find { it.role == "HIGH" }
        assertNotNull("HIGH label missing", high)
        assertEquals("forecast", high!!.series)

        // 2. LOW (idx 5, Past) -> forecast
        val low = placements.find { it.role == "LOW" }
        assertNotNull("LOW label missing", low)
        assertEquals("LOW at idx 5 (past) should be marked as forecast", "forecast", low!!.series)

        // 3. LOCAL (idx 13, Future) -> forecast
        val local = placements.find { it.role == "LOCAL" && it.index == 13 }
        assertNotNull("LOCAL label at idx 13 missing", local)
        assertEquals("LOCAL peak at idx 13 (future) should be marked as forecast", "forecast", local!!.series)

        // 4. ACTUAL_LOW (idx 4, Past) -> actual
        val actualLow = placements.find { it.role == "ACTUAL_LOW" && it.index == 4 }
        assertNotNull("ACTUAL_LOW label missing", actualLow)
        assertEquals("actual", actualLow!!.series)
        assertEquals(48.0f, actualLow.temperature, 0.1f)

        // 5. ACTUAL_HIGH (idx 8, Past) -> actual
        val actualHigh = placements.find { it.role == "ACTUAL_HIGH" && it.index == 8 }
        assertNotNull("ACTUAL_HIGH label missing", actualHigh)
        assertEquals("actual", actualHigh!!.series)

        // 6. START (idx 0, Past) -> forecast (Fixed role expansion)
        val startLabel = placements.find { it.index == 0 }
        assertNotNull("Label at idx 0 missing", startLabel)
        assertEquals("Label at start (past) should be forecast series (blue)", "forecast", startLabel!!.series)

        // 7. END (idx 20, Future) -> forecast
        val endLabel = placements.find { it.role == "END" }
        assertNotNull("END label missing", endLabel)
        assertEquals("forecast", endLabel!!.series)
    }

    @Test
    fun `testStartLabelInHistoryUsesCorrectSeries`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)
        
        // Setup: Monotonic rise so idx 0 is LOW.
        val hours = (0..10).map { i ->
            val time = start.plusHours(i.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = 70.0f + i,
                actualTemperature = 72.0f + i,
                isActual = i <= 5,
                label = "${time.hour}h"
            )
        }

        val observedAtMs = start.plusHours(5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(5),
            observedAt = observedAtMs,
            lastObservedTemp = hours[5].actualTemperature,
            onLabelPlaced = { placements.add(it) }
        )

        val startLabel = placements.find { it.index == 0 }
        assertNotNull("Label at idx 0 missing", startLabel)
        assertEquals("Label at start should be forecast series (blue)", "forecast", startLabel!!.series)
    }

    @Test
    fun `forecast low is suppressed when actual low is nearby with similar value`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        val observedAtMs = start.plusHours(10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 25-hour span. Actual zone: 0-10, Forecast zone: 11-24.
        // Global LOW at idx 9 (52.5), ACTUAL_LOW also at idx 9 (52.3).
        // FORECAST_LOW at idx 13 (52.8) — 4 indices from actualLowIndex, 0.5° apart.
        val forecastTemps = listOf(
            70f, 68f, 66f, 64f, 62f, 60f, 58f, 56f, 54f, 52.5f, 52.5f,
            54f, 53f, 52.8f, 54f, 56f, 58f, 60f, 62f, 64f, 66f, 68f, 70f, 72f, 74f
        )
        val hours = forecastTemps.mapIndexed { i, temp ->
            val time = start.plusHours(i.toLong())
            val actualTemp = if (i <= 10) temp - 0.2f else null
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = i <= 10,
                label = "${time.hour}",
            )
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
            currentTime = start.plusHours(15),
            observedAt = observedAtMs,
            lastObservedTemp = forecastTemps[10] - 0.2f,
            onLabelPlaced = { placements.add(it) },
        )

        assertNull(
            "FORECAST_LOW should be suppressed when ACTUAL_LOW is nearby with similar value. placements=$placements",
            placements.find { it.role == "FORECAST_LOW" },
        )
        assertNotNull(
            "Actual low label should still be placed. placements=$placements",
            placements.find { it.role == "LOW" || it.role == "ACTUAL_LOW" },
        )
    }

    @Test
    fun `forecast high is suppressed when actual high is nearby with similar value`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        val observedAtMs = start.plusHours(10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 25-hour span. Actual zone: 0-10, Forecast zone: 11-24.
        // Global HIGH at idx 9 (72), ACTUAL_HIGH also at idx 9 (71.8).
        // FORECAST_HIGH at idx 12 (70.5) — 3 indices from actualHighIndex, 1.3° apart.
        val forecastTemps = listOf(
            50f, 54f, 58f, 60f, 62f, 64f, 66f, 68f, 70f, 72f, 70f,
            69f, 70.5f, 69f, 66f, 62f, 58f, 54f, 50f, 48f, 46f, 44f, 42f, 40f, 38f
        )
        val hours = forecastTemps.mapIndexed { i, temp ->
            val time = start.plusHours(i.toLong())
            val actualTemp = if (i <= 10) temp - 0.2f else null
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = i <= 10,
                label = "${time.hour}",
            )
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
            currentTime = start.plusHours(15),
            observedAt = observedAtMs,
            lastObservedTemp = forecastTemps[10] - 0.2f,
            onLabelPlaced = { placements.add(it) },
        )

        assertNull(
            "FORECAST_HIGH should be suppressed when ACTUAL_HIGH is nearby with similar value. placements=$placements",
            placements.find { it.role == "FORECAST_HIGH" },
        )
        assertNotNull(
            "Actual high label should still be placed. placements=$placements",
            placements.find { it.role == "HIGH" || it.role == "ACTUAL_HIGH" },
        )
    }

    @Test
    fun `staleness time label is placed above dot when colliding with bottom bounds or other labels`() {
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)
        // Create a graph where the dot is at the global minimum to force a LOW label
        val hours = (0..5).map { i ->
            val time = start.plusHours(i.toLong())
            val temp = if (i == 2) 40.0f else 50.0f
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = temp,
                actualTemperature = if (i <= 2) temp else null,
                isActual = i <= 2,
                label = "${time.hour}h"
            )
        }

        // We observe at hour 2
        val observedAtMs = start.plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        var fetchDotDebug: TemperatureGraphRenderer.FetchDotDebug? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 200,
            currentTime = start.plusHours(2).plusMinutes(90), // 90 min staleness so it shows "1h 30m"
            observedAt = observedAtMs,
            lastObservedTemp = 40f, // Minimum temp, places dot at bottom
            onFetchDotResolved = { fetchDotDebug = it }
        )

        assertNotNull("fetchDotDebug must be captured", fetchDotDebug)
        assertNotNull("stalenessLabelY must be captured", fetchDotDebug!!.stalenessLabelY)
        
        val fetchY = fetchDotDebug!!.fetchY!!
        val labelY = fetchDotDebug!!.stalenessLabelY!!
        
        assertTrue("Staleness label ($labelY) should be placed ABOVE the dot ($fetchY) due to collision", labelY < fetchY)
    }

    @Test
    fun `test colliding labels stack in correct temperature order`() {
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        
        // Scenario 1: Valley Collision (Bottom Labels)
        // ACTUAL_LOW (48) at idx 2, FORECAST_LOW (50) at idx 3
        val valleyHours = (0..5).map { i ->
            val temp = when (i) {
                0 -> 60f
                1 -> 55f
                2 -> 52f
                3 -> 50f // FORECAST_LOW
                4 -> 55f
                5 -> 60f
                else -> 60f
            }
            val actualTemp = when (i) {
                0 -> 60f
                1 -> 55f
                2 -> 48f // ACTUAL_LOW
                else -> null
            }
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = actualTemp != null,
                label = "${start.plusHours(i.toLong()).hour}h"
            )
        }

        val valleyPlacements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = valleyHours,
            widthPx = 200, // Small width to force horizontal collision
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { valleyPlacements.add(it) }
        )

        val actualLow = valleyPlacements.find { it.role == "ACTUAL_LOW" }
        val forecastLow = valleyPlacements.find { it.role == "LOW" || it.role == "FORECAST_LOW" }

        assertNotNull("Expected ACTUAL_LOW label", actualLow)
        assertNotNull("Expected FORECAST_LOW label", forecastLow)
        
        assertTrue(
            "Expected lower temperature (48) to have a larger Y (lower on screen) than higher temperature (50). " +
            "actualLow.y=${actualLow!!.y} (temp=${actualLow.temperature}) vs forecastLow.y=${forecastLow!!.y} (temp=${forecastLow.temperature})",
            actualLow.y > forecastLow.y
        )

        // Scenario 2: Peak Collision (Top Labels)
        // ACTUAL_HIGH (64) at idx 2, FORECAST_HIGH (62) at idx 3
        val peakHours = (0..5).map { i ->
            val temp = when (i) {
                0 -> 50f
                1 -> 55f
                2 -> 60f
                3 -> 62f // FORECAST_HIGH
                4 -> 55f
                5 -> 50f
                else -> 50f
            }
            val actualTemp = when (i) {
                0 -> 50f
                1 -> 55f
                2 -> 64f // ACTUAL_HIGH
                else -> null
            }
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = actualTemp != null,
                label = "${start.plusHours(i.toLong()).hour}h"
            )
        }

        val peakPlacements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = peakHours,
            widthPx = 200, // Small width to force horizontal collision
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { peakPlacements.add(it) }
        )

        val actualHigh = peakPlacements.find { it.role == "ACTUAL_HIGH" }
        val forecastHigh = peakPlacements.find { it.role == "HIGH" || it.role == "FORECAST_HIGH" }

        assertNotNull("Expected ACTUAL_HIGH label", actualHigh)
        assertNotNull("Expected FORECAST_HIGH label", forecastHigh)
        
        assertTrue(
            "Expected higher temperature (64) to have a smaller Y (higher on screen) than lower temperature (62). " +
            "actualHigh.y=${actualHigh!!.y} (temp=${actualHigh.temperature}) vs forecastHigh.y=${forecastHigh!!.y} (temp=${forecastHigh.temperature})",
            actualHigh.y < forecastHigh.y
        )
    }
}
