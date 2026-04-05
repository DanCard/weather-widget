package com.weatherwidget.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
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

        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.2f, actualTemperature = 84.2f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 89.0f, actualTemperature = 85.1f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 87.2f, actualTemperature = 86.2f, isActual = true, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 84.3f, actualTemperature = 84.0f, isActual = true, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 81.0f, label = "7p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(3),
            onLabelPlaced = { placements.add(it) },
        )

        val actualHigh = placements.find { it.role == "HIGH" }
        val forecastHigh = placements.find { it.role == "FORECAST_HIGH" }

        assertNotNull("Expected actual-series HIGH label. placements=$placements", actualHigh)
        assertNotNull("Expected forecast-series FORECAST_HIGH label. placements=$placements", forecastHigh)
        assertEquals(86.2f, actualHigh!!.temperature, 0.01f)
        assertEquals(89.0f, forecastHigh!!.temperature, 0.01f)
        assertEquals("actual", actualHigh.series)
        assertEquals("forecast", forecastHigh.series)
        assertEquals("actual", actualHigh.colorFamily)
        assertEquals("forecast", forecastHigh.colorFamily)
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

        assertNull("END should be suppressed when HIGH already labels the final point. placements=$placements", placements.find { it.role == "END" })
        assertNotNull("HIGH should remain when it already labels the final point. placements=$placements", placements.find { it.role == "HIGH" })
    }

    @Test
    fun `end label is suppressed when adjacent local label would crowd the endpoint`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 52.0f, actualTemperature = 53.5f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 81.0f, actualTemperature = 82.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 79.0f, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 77.0f, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 55.0f, label = "7p"),
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

        assertNotNull("Expected adjacent local label near endpoint. placements=$placements", placements.find { it.role == "LOCAL" })
        assertNull("END should be suppressed when another label is adjacent to the endpoint. placements=$placements", placements.find { it.role == "END" })
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
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 63.0f, label = "10a"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 60.0f, actualTemperature = 55.0f, isActual = true, label = "11a"), // LOW
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 58.0f, label = "12p"), // FORECAST_LOW
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 65.0f, label = "1p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { placements.add(it) },
        )

        val dailyLow = placements.find { it.role == "LOW" }
        val forecastLow = placements.find { it.role == "FORECAST_LOW" }

        assertNotNull("Expected actual LOW label", dailyLow)
        assertNotNull("Expected FORECAST_LOW label", forecastLow)
        assertEquals(55.0f, dailyLow!!.temperature, 0.01f)
        assertEquals(58.0f, forecastLow!!.temperature, 0.01f)
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
        // fallback (above) collides with icon.
        // Logic should FORCE it above.
        // Note: After refactor, reason is "FORCED" when collision exists on final placement
        assertTrue("Expected label above line", lowLabel!!.placedAbove)
        assertEquals("FORCED", lowLabel.reason)
    }
}
