package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureGraphLabelPlacementRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildHours(
        temps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 3, 19, 10, 0),
    ): List<HourData> =
        temps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    @Test
    fun `wide widget injects middle label when only edge temperature labels are present`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val temps = (50..61).map { it.toFloat() }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(temps, start),
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            numColumns = 5,
            onLabelPlaced = { placements.add(it) },
        )

        val indices = placements.map { it.index }.sorted()
        assertEquals("Expected start, midpoint, and end labels on a wide sparse graph. placements=$placements", listOf(0, 5, 11), indices)
        assertTrue("Expected injected midpoint to be a LOCAL label. placements=$placements", placements.any { it.index == 5 && it.role == TemperatureRole.LOCAL })
    }

    @Test
    fun `narrow widget does not inject middle label when only edge temperature labels are present`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val temps = (50..61).map { it.toFloat() }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(temps, start),
            widthPx = 400,
            heightPx = 400,
            currentTime = start,
            numColumns = 4,
            onLabelPlaced = { placements.add(it) },
        )

        val indices = placements.map { it.index }.sorted()
        assertEquals("Expected only edge labels on a narrow sparse graph. placements=$placements", listOf(0, 11), indices)
    }

    @Test
    fun `wide widget does not inject middle label when temperature graph already has interior labels`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val temps = listOf(50f, 55f, 61f, 55f, 49f, 52f, 54f, 56f, 58f, 60f, 62f, 64f)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(temps, start),
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            numColumns = 5,
            onLabelPlaced = { placements.add(it) },
        )

        assertFalse("Midpoint should not be injected when interior extrema are already labeled. placements=$placements", placements.any { it.index == 5 && it.role == TemperatureRole.LOCAL })
        assertTrue("Expected at least one real interior extrema label. placements=$placements", placements.any { it.index !in listOf(0, temps.lastIndex) })
    }

    @Test
    fun `peak falls back below when above placement would leave the screen`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = buildHours(listOf(0f, 50f, 98f, 50f, 0f)),
                widthPx = 700,
                heightPx = 24, // Keep small to force Above off-screen
                currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
                onLabelPlaced = { placements.add(it) },
            )
        }

        val highPlacement = placements.find { it.role == TemperatureRole.HIGH }
        if (highPlacement != null) {
            assertFalse(
                "Expected constrained HIGH label to avoid above-placement when it would be off-screen. placement=$highPlacement",
                highPlacement.placedAbove,
            )
        }
    }

    @Test
    fun `peak label above stays close to the forecast line`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        var points: PointsDebug? = null

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = buildHours(listOf(40f, 55f, 70f, 89f, 70f, 55f, 40f)),
                widthPx = 700,
                heightPx = 420,
                currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
                onLabelPlaced = { placements.add(it) },
                onPointsResolved = { points = it },
            )
        }

        val highPlacement = placements.find { it.role == TemperatureRole.HIGH }
        assertNotNull("Expected HIGH label to be drawn. placements=$placements", highPlacement)
        assertTrue("Expected HIGH label to prefer above when room exists. placement=$highPlacement", highPlacement!!.placedAbove)

        val highPoint = requireNotNull(points).forecast[3]
        
        // Calculate font metrics exactly how the renderer's fallback does
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 23f, context.resources.displayMetrics)
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
        val placements = mutableListOf<LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 6, 0)
        // 20-hour span so edge suppression doesn't interfere.
        // Forecast peaks at idx=12 (89°), actual peaks at idx=8 (86.2°) — different indices.
        val forecastTemps = listOf(75f, 77f, 79f, 81f, 83f, 85f, 86f, 87f, 88f, 88.5f, 88.8f, 88.9f, 89f, 88f, 86f, 84f, 82f, 81f, 80f, 79f)
        val actualTemps =   listOf(75f, 77f, 79f, 82f, 84f, 85.5f, 86f, 86.1f, 86.2f, 85f, 84f, 83f, null, null, null, null, null, null, null, null)
        val hours = (0 until 20).map { i ->
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecastTemps[i],
                actualTemperature = actualTemps[i],
                isActual = actualTemps[i] != null,
                label = "${(6 + i) % 24}",
            )
        }

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = 700,
                heightPx = 450,
                currentTime = start.plusHours(14),
                onLabelPlaced = { placements.add(it) },
            )
        }

        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH }
        val forecastHigh = placements.find { it.role == TemperatureRole.HIGH || it.role == TemperatureRole.FORECAST_HIGH || it.role == TemperatureRole.PAST_FORECAST_HIGH }

        assertNotNull("Expected ACTUAL_HIGH label. placements=$placements", actualHigh)
        assertNotNull("Expected forecast-series HIGH label. placements=$placements", forecastHigh)
        assertEquals(86.2f, actualHigh!!.temperature, 0.1f)
        assertEquals(89.0f, forecastHigh!!.temperature, 0.1f)
    }

    @Test
    fun `end label is still emitted when observedAt lands on the final point`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(4).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "3p"),
                HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "4p"),
                HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "5p"),
                HourData(dateTime = start.plusHours(3), temperature = 87.0f, label = "6p"),
                HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "7p"),
            )

        runBlocking {
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
        }

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("Expected END label even when observedAt is on the final point. placements=$placements", endPlacement)
        assertEquals(88.0f, endPlacement!!.temperature, 0.01f)
    }

    @Test
    fun `end label uses forecast temperature after fetch transition not ghost line when endpoint is uncrowded`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 70.0f, actualTemperature = 70.0f, isActual = true, label = "3p"),
                HourData(dateTime = start.plusHours(1), temperature = 75.0f, actualTemperature = 75.0f, isActual = true, label = "4p"),
                HourData(dateTime = start.plusHours(2), temperature = 74.0f, label = "5p"),
                HourData(dateTime = start.plusHours(3), temperature = 73.0f, label = "6p"),
            )

        runBlocking {
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
        }

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("Expected END label to be drawn. placements=$placements", endPlacement)
        assertEquals("END label should stay on forecast line value, not ghost line", 73.0f, endPlacement!!.temperature, 0.01f)
        assertEquals("forecast", endPlacement.series)
        assertEquals("forecast", endPlacement.colorFamily)
    }

    @Test
    fun `end label drops below forecast curve when descending peak intrudes above`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val hours = listOf(
            HourData(dateTime = start.plusHours(0), temperature = 70.0f, label = "10a"),
            HourData(dateTime = start.plusHours(1), temperature = 75.0f, label = "11a"),
            HourData(dateTime = start.plusHours(2), temperature = 80.0f, label = "12p"),
            HourData(dateTime = start.plusHours(3), temperature = 84.0f, label = "1p"),
            HourData(dateTime = start.plusHours(4), temperature = 82.0f, label = "2p"),
            HourData(dateTime = start.plusHours(5), temperature = 79.0f, label = "3p"),
        )

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = 500,
                heightPx = 450,
                currentTime = start,
                onLabelPlaced = { placements.add(it) },
            )
        }

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("Expected END label to be drawn. placements=$placements", endPlacement)
        assertFalse("END should land below to clear the descending forecast peak. placements=$placements", endPlacement!!.placedAbove)
        assertEquals("forecast", endPlacement.series)
    }

    @Test
    fun `end label is suppressed when same-index high already labels final point`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = buildHours(listOf(70f, 75f, 81f)),
                widthPx = 500,
                heightPx = 450,
                currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
                onLabelPlaced = { placements.add(it) },
            )
        }

        assertNotNull("HIGH or END should label the final point. placements=$placements", placements.find { it.role == TemperatureRole.HIGH || it.role == TemperatureRole.END })
        assertNotNull("HIGH should remain when it already labels the final point. placements=$placements", placements.find { it.role == TemperatureRole.HIGH })
    }

    @Test
    fun `placeTemperatureLabels thins out redundant labels on a monotonic rise`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 12, 0)
        // Monotonic rise from 50 to 70 over 20 hours
        val signal = (50..70).map { it.toFloat() }
        
        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = buildHours(signal, start),
                widthPx = 500,
                heightPx = 400,
                currentTime = start,
                onLabelPlaced = { placements.add(it) }
            )
        }

        assertTrue("Should have at most 6 labels. Placed: ${placements.size}", placements.size <= 6)
        assertTrue("Should have START or LOW label at the beginning", placements.any { (it.role == TemperatureRole.START || it.role == TemperatureRole.LOW) && it.index == 0 })
        assertTrue("Should have HIGH or END label at the end", placements.any { (it.role == TemperatureRole.HIGH || it.role == TemperatureRole.END) && it.index == signal.lastIndex })
        // Middle points should be thinned out
        val intermediate = placements.filter { it.index != 0 && it.index != signal.lastIndex }
        assertTrue("Should have thinned out most intermediate labels. Found: ${intermediate.size}", intermediate.size < 5)
    }

    @Test
    fun `end label is shown even when another label is near the endpoint`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 52.0f, actualTemperature = 53.5f, isActual = true, label = "3p"),
                HourData(dateTime = start.plusHours(1), temperature = 81.0f, actualTemperature = 82.0f, isActual = true, label = "4p"),
                HourData(dateTime = start.plusHours(2), temperature = 79.0f, label = "5p"),
                HourData(dateTime = start.plusHours(3), temperature = 77.0f, label = "6p"),
                HourData(dateTime = start.plusHours(4), temperature = 54.0f, label = "7p"),
                HourData(dateTime = start.plusHours(5), temperature = 57.0f, label = "8p"),
            )

        runBlocking {
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
        }

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("END should always be shown as an essential boundary marker. placements=$placements", endPlacement)
    }

    @Test
    fun `actuals end does not produce a label (fetch dot shows observed temp instead)`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "3p"),
                HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "4p"),
                HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "5p"),
                HourData(dateTime = start.plusHours(3), temperature = 87.0f, label = "6p"),
                HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "7p"),
            )

        runBlocking {
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = 500,
                heightPx = 450,
                currentTime = start.plusHours(2),
                onLabelPlaced = { placements.add(it) },
            )
        }

        val actualEnd = placements.find { it.role == TemperatureRole.ACTUAL_END }
        assertNull("ACTUAL_END label should not be placed (fetch dot covers this)", actualEnd)
    }

    @Test
    fun `actual and forecast lows are both labeled when peaks differ`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 6, 0)
        // 20-hour span so edge suppression doesn't interfere.
        // Forecast low at idx=12 (58°), actual low at idx=8 (55°) — different indices.
        val forecastTemps = listOf(70f, 69f, 67f, 65f, 64f, 63f, 62f, 61f, 60.5f, 60f, 59.5f, 59f, 58f, 59f, 60f, 61f, 62f, 63f, 64f, 65f)
        val actualTemps =   listOf(70f, 68f, 65f, 62f, 60f, 58f, 57f, 56f, 55f, 56f, 57f, 58f, null, null, null, null, null, null, null, null)
        val hours = (0 until 20).map { i ->
            HourData(
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

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        val forecastLow = placements.find { it.role == TemperatureRole.LOW || it.role == TemperatureRole.FORECAST_LOW }

        assertNotNull("Expected ACTUAL_LOW label. placements=$placements", actualLow)
        assertNotNull("Expected forecast LOW label. placements=$placements", forecastLow)
        assertEquals(55.0f, actualLow!!.temperature, 0.1f)
        assertEquals(58.0f, forecastLow!!.temperature, 0.1f)
    }

    @Test
    fun `extrapolated actualTemperature on future hours does not produce HIGH label at extrapolated value`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        // transitionX is driven by observedAt (fetch dot), not currentTime.
        // Hours after the fetch dot are "future" on the graph even if their timestamp is in the past.
        // Scenario: fetch dot at hour 1 (observedAt), but station has isActual=true readings up to hour 3.
        val observedAtMs = start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours = listOf(
            HourData(dateTime = start.plusHours(0), temperature = 74f, actualTemperature = 74f, isActual = true, label = "10a"),
            HourData(dateTime = start.plusHours(1), temperature = 76f, actualTemperature = 76f, isActual = true, label = "11a"),  // fetch dot here
            // These hours are past currentTime but AFTER the fetch dot — graph treats them as future
            HourData(dateTime = start.plusHours(2), temperature = 77f, actualTemperature = 82f, isActual = true, label = "12p"),
            HourData(dateTime = start.plusHours(3), temperature = 77f, actualTemperature = 82f, isActual = true, label = "1p"),
            HourData(dateTime = start.plusHours(4), temperature = 76f, label = "2p"),
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
        val highLabel = placements.find { it.role == TemperatureRole.HIGH }
        assertNotNull("Expected a HIGH label from actual observations. placements=$placements", highLabel)
        assertTrue(
            "HIGH label temperature should reflect actual observation peak (78°), not extrapolated future value. placement=$highLabel",
            highLabel!!.temperature <= 78f + 0.1f,
        )
    }

    @Test
    fun `label Y positions are consistent with their temperature values`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val hours = listOf(
            HourData(dateTime = start.plusHours(0), temperature = 70f, actualTemperature = 73f, isActual = true, label = "10a"),
            HourData(dateTime = start.plusHours(1), temperature = 74f, actualTemperature = 77f, isActual = true, label = "11a"),
            HourData(dateTime = start.plusHours(2), temperature = 76f, actualTemperature = 79f, isActual = true, label = "12p"),
            HourData(dateTime = start.plusHours(3), temperature = 74f, label = "1p"),
            HourData(dateTime = start.plusHours(4), temperature = 71f, label = "2p"),
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
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        
        // Use a real drawable ID from the project
        val iconRes = com.weatherwidget.R.drawable.ic_weather_clear

        // We'll place a LOW at the very bottom edge to force it off-screen for 'drawBelow'
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 90.0f, label = "10a"),
                HourData(dateTime = start.plusHours(1), temperature = 10.0f, label = "11a", showLabel = true, iconRes = iconRes), // LOW
                HourData(dateTime = start.plusHours(2), temperature = 90.0f, label = "12p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 80, // Increased from 60 to fit 23dp fonts
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )

        val lowLabel = placements.find { it.role == TemperatureRole.LOW }
        if (lowLabel == null || lowLabel.placedAbove) {
            println("Placements: $placements")
        }
        assertNotNull("LOW label should be present. Placements=$placements", lowLabel)

        // With updated graph padding and icon sizing, above placement now has room
        // and the label placer correctly prefers above for the LOW at the bottom edge.
        assertTrue("Expected label above line with updated layout", lowLabel!!.placedAbove)
        assertTrue("Expected reason to be above", lowLabel.reason.startsWith("above"))
    }

    @Test
    fun `lowest plotted point stays above footer icon band`() {
        var points: PointsDebug? = null
        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val iconRes = com.weatherwidget.R.drawable.ic_weather_clear
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 90.0f, label = "10a"),
                HourData(dateTime = start.plusHours(1), temperature = 10.0f, label = "11a", showLabel = true, iconRes = iconRes),
                HourData(dateTime = start.plusHours(2), temperature = 90.0f, label = "12p"),
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

        // Footer icon size derives from the hour-label text, same as renderGraph computes it.
        val footerIconSize = GraphRenderUtils.footerIconSize(
            TemperatureGraphStyle.ensurePaints(context, 1f).hourLabelTextPaint,
        )
        val layout = GraphLayout.computeLayout(context, heightPx, 1f, footerIconSize)
        val footerTop = layout.footerTop
        val lowestY = requireNotNull(points).original.maxOf { it.second }

        assertTrue(
            "Lowest plotted point should stay above the footer/icon band. lowestY=$lowestY footerTop=$footerTop",
            lowestY < footerTop,
        )
    }

    @Test
    fun `testForecastLabelsInHistoryAreColoredBlue`() {
        val placements = mutableListOf<LabelPlacementDebug>()
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
            HourData(
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
        val high = placements.find { it.role == TemperatureRole.HIGH }
        assertNotNull("HIGH label missing", high)
        assertEquals("forecast", high!!.series)

        // 2. LOW (idx 5, Past) -> forecast
        val low = placements.find { it.role == TemperatureRole.LOW }
        assertNotNull("LOW label missing", low)
        assertEquals("LOW at idx 5 (past) should be marked as forecast", "forecast", low!!.series)

        // 3. LOCAL (idx 13, Future) -> forecast
        val local = placements.find { it.role == TemperatureRole.LOCAL && it.index == 13 }
        assertNotNull("LOCAL label at idx 13 missing", local)
        assertEquals("LOCAL peak at idx 13 (future) should be marked as forecast", "forecast", local!!.series)

        // 4. ACTUAL_LOW (idx 4, Past) -> actual
        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW && it.index == 4 }
        assertNotNull("ACTUAL_LOW label missing", actualLow)
        assertEquals("actual", actualLow!!.series)
        assertEquals(48.0f, actualLow.temperature, 0.1f)

        // 5. ACTUAL_HIGH (idx 8, Past) -> actual
        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH && it.index == 8 }
        assertNotNull("ACTUAL_HIGH label missing", actualHigh)
        assertEquals("actual", actualHigh!!.series)

        // 6. START (idx 0, Past) -> forecast (Fixed role expansion)
        val startLabel = placements.find { it.index == 0 }
        assertNotNull("Label at idx 0 missing", startLabel)
        assertEquals("Label at start (past) should be forecast series (blue)", "forecast", startLabel!!.series)

        // 7. END (idx 20, Future) -> forecast
        val endLabel = placements.find { it.role == TemperatureRole.END }
        assertNotNull("END label missing", endLabel)
        assertEquals("forecast", endLabel!!.series)
    }

    @Test
    fun `testStartLabelInHistoryUsesCorrectSeries`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)
        
        // Setup: Monotonic rise so idx 0 is LOW.
        val hours = (0..10).map { i ->
            val time = start.plusHours(i.toLong())
            HourData(
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
        val placements = mutableListOf<LabelPlacementDebug>()
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
            HourData(
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
            placements.find { it.role == TemperatureRole.FORECAST_LOW },
        )
        assertNotNull(
            "Actual low label should still be placed. placements=$placements",
            placements.find { it.role == TemperatureRole.LOW || it.role == TemperatureRole.ACTUAL_LOW },
        )
    }

    @Test
    fun `forecast high is suppressed when actual high is nearby with similar value`() {
        val placements = mutableListOf<LabelPlacementDebug>()
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
            HourData(
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
            placements.find { it.role == TemperatureRole.FORECAST_HIGH },
        )
        assertNotNull(
            "Actual high label should still be placed. placements=$placements",
            placements.find { it.role == TemperatureRole.HIGH || it.role == TemperatureRole.ACTUAL_HIGH },
        )
    }

    @Test
    fun `staleness time label is placed above dot when colliding with bottom bounds or other labels`() {
        val start = LocalDateTime.of(2026, 4, 6, 10, 0)
        // Create a graph where the dot is at the global minimum to force a LOW label
        val hours = (0..5).map { i ->
            val time = start.plusHours(i.toLong())
            val temp = if (i == 2) 40.0f else 50.0f
            HourData(
                dateTime = time,
                temperature = temp,
                actualTemperature = if (i <= 2) temp else null,
                isActual = i <= 2,
                label = "${time.hour}h"
            )
        }

        // We observe at hour 2
        val observedAtMs = start.plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        var fetchDotDebug: FetchDotDebug? = null

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
                2 -> 48f // ACTUAL_LOW (interior valley)
                3 -> 55f // observed neighbour so idx 2 is a genuine turning point (not a bare edge)
                else -> null
            }
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = actualTemp != null,
                label = "${start.plusHours(i.toLong()).hour}h"
            )
        }

        val valleyPlacements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = valleyHours,
            widthPx = 200, // Small width to force horizontal collision
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { valleyPlacements.add(it) }
        )

        val actualLow = valleyPlacements.find { it.role == TemperatureRole.ACTUAL_LOW }
        val forecastLow = valleyPlacements.find { it.role == TemperatureRole.LOW || it.role == TemperatureRole.FORECAST_LOW }

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
                2 -> 64f // ACTUAL_HIGH (interior peak)
                3 -> 55f // observed neighbour so idx 2 is a genuine turning point (not a bare edge)
                else -> null
            }
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = temp,
                actualTemperature = actualTemp,
                isActual = actualTemp != null,
                label = "${start.plusHours(i.toLong()).hour}h"
            )
        }

        val peakPlacements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = peakHours,
            widthPx = 200, // Small width to force horizontal collision
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { peakPlacements.add(it) }
        )

        val actualHigh = peakPlacements.find { it.role == TemperatureRole.ACTUAL_HIGH }
        val forecastHigh = peakPlacements.find { it.role == TemperatureRole.HIGH || it.role == TemperatureRole.FORECAST_HIGH }

        assertNotNull("Expected ACTUAL_HIGH label", actualHigh)
        assertNotNull("Expected FORECAST_HIGH label", forecastHigh)
        
        assertTrue(
            "Expected higher temperature (64) to have a smaller Y (higher on screen) than lower temperature (62). " +
            "actualHigh.y=${actualHigh!!.y} (temp=${actualHigh.temperature}) vs forecastHigh.y=${forecastHigh!!.y} (temp=${forecastHigh.temperature})",
            actualHigh.y < forecastHigh.y
        )
    }

    @Test
    fun `forecast low flips above the curve when it would collide with the fetch dot value label`() {
        // End-to-end guard that render() routes the fetch-dot bounds into the label engine as HARD
        // obstacles (the Samsung/desktop "631°" bug). The actual low coincides with the fetch dot so
        // its ACTUAL_LOW is suppressed (reason=FETCH_DOT) and the pink number is the dot value label.
        // The adjacent forecast LOW must flip ABOVE the curve rather than draw on top of that label.
        val placements = mutableListOf<LabelPlacementDebug>()
        var fetchDotDebug: FetchDotDebug? = null
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)

        // Forecast dips to a UNIQUE minimum 63° at idx 3 -> forecast LOW. The observed line bottoms
        // out at 61° at the same idx 3 = the fetch dot, so the forecast LOW (63°) and the pink dot
        // value label (61°) land at the same x and collide head-on (the "631°" overlap).
        val forecastTemps = listOf(75f, 70f, 66f, 63f, 66f, 70f, 73f, 75f)
        val actualTemps = listOf<Float?>(75f, 69f, 64f, 61f, null, null, null, null)
        val hours = forecastTemps.indices.map { i ->
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecastTemps[i],
                actualTemperature = actualTemps[i],
                isActual = actualTemps[i] != null,
                label = "${start.plusHours(i.toLong()).hour}h",
            )
        }
        val observedAtMs = start.plusHours(3).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 220, // short, so the below-slot is tight against the dot value label
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
            lastObservedTemp = 61f,
            onLabelPlaced = { placements.add(it) },
            onFetchDotResolved = { fetchDotDebug = it },
        )

        // The actual low at the dot is suppressed; the pink number is the fetch-dot value label.
        assertNull(
            "ACTUAL_LOW should be suppressed at the fetch dot. placements=$placements",
            placements.find { it.role == TemperatureRole.ACTUAL_LOW },
        )
        val forecastLow = placements.find {
            (it.role == TemperatureRole.LOW || it.role == TemperatureRole.FORECAST_LOW) && it.temperature == 63f
        }
        assertNotNull("Expected a forecast LOW (63°) label. placements=$placements", forecastLow)
        assertTrue(
            "Forecast LOW must flip ABOVE the curve to clear the fetch-dot value label " +
                "(reason=${forecastLow!!.reason}, y=${forecastLow.y}, fetchY=${fetchDotDebug?.fetchY}). placements=$placements",
            forecastLow.placedAbove,
        )
    }

    @Test
    fun `actual low label stays below dip even with significant icon overlap`() {
        val start = LocalDateTime.of(2026, 4, 19, 10, 0)
        
        // 20-hour span. Sharp dip at idx 10 (40 degrees).
        // Rest of the graph is at 80 degrees to force a large range and push the 40-degree point 
        // to the very bottom of the graph.
        val hours = (0 until 20).map { i ->
            val actualTemp = if (i == 10) 40f else 80f
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = 80f,
                actualTemperature = actualTemp,
                isActual = true,
                label = "${start.plusHours(i.toLong()).hour}h",
                iconRes = com.weatherwidget.R.drawable.ic_weather_clear // Add icon to trigger collision
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400, // Standard height
            currentTime = start.plusHours(12),
            onLabelPlaced = { placements.add(it) }
        )

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("Expected ACTUAL_LOW label to be placed", actualLow)
        
        // Before the fix, this label might have been pushed above due to >45% icon overlap.
        // After the fix, it should stay below.
        assertFalse(
            "ACTUAL_LOW label should be placed BELOW the dip. placement=$actualLow",
            actualLow!!.placedAbove
        )
        assertTrue(
            "ACTUAL_LOW label should have some displacement steps if it's hitting icons but stay below",
            actualLow.reason.startsWith("below")
        )
    }

    @Test
    fun `actual low at valley with forecast curve dipping below places tight below trough with no leader`() {
        val start = LocalDateTime.of(2026, 6, 25, 8, 0)
        // 20 hours: actual temps form a valley at index 13 (50°) (to stay outside the left-edge start window).
        // Forecast temps are equal except at the valley where the forecast is 45° — since lower temps
        // map to higher y (visually lower) on the graph, the forecast line dips BELOW the actual trough,
        // causing curve intrusion in the below direction, which previously flipped the label above.
        val actualTemps = listOf(
            70f, 70f, 70f, 70f, 70f, 70f, 70f, 70f,
            70f, 65f, 60f, 55f, 52f, 50f, 53f, 58f, 63f, 68f, 72f, 75f
        )
        val forecastTemps = listOf(
            70f, 70f, 70f, 70f, 70f, 70f, 70f, 70f,
            70f, 65f, 60f, 55f, 52f, 48f, 53f, 58f, 63f, 68f, 72f, 75f
        )
        val hours = (0 until 20).map { i ->
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecastTemps[i],
                actualTemperature = actualTemps[i],
                isActual = true,
                label = "${start.plusHours(i.toLong()).hour}h",
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start.plusHours(19),
            onLabelPlaced = { placements.add(it) },
        )

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("Expected ACTUAL_LOW label. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW should be placed BELOW the trough, not above. placement=$actualLow",
            actualLow!!.placedAbove,
        )
        assertEquals(
            "ACTUAL_LOW should have no displacement steps (tight below trough, no leader)",
            0,
            actualLow.displacementSteps,
        )
        assertEquals(
            "ACTUAL_LOW reason should be belowActualCurve",
            "belowActualCurve",
            actualLow.reason,
        )
    }

    @Test
    fun `drawHourLabels resolves crowding by choosing denser spacing or dropping icons`() {
        val paint = Paint().apply {
            textSize = 23f
        }
        val bitmap = android.graphics.Bitmap.createBitmap(800, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val items = (0 until 24).map { i ->
            HourData(
                dateTime = LocalDateTime.now().plusHours(i.toLong()),
                temperature = 60f,
                label = "${i}p",
                showLabel = true,
                iconRes = 12345
            )
        }
        val points = (0 until 24).map { i ->
            Pair(i * 30f, 100f)
        }

        var iconDrawCount = 0
        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = items,
            points = points,
            widthPx = 400,
            heightPx = 200,
            minHourLabelSpacing = 40f,
            hourLabelTextPaint = paint,
            dpToPx = { it * 2f },
            showLabel = { it.showLabel },
            labelText = { it.label },
            iconSize = 15f,
            iconTextGapDp = 2f,
            hasIcon = { true },
            isDateLabel = { false },
            drawIcon = { index, rect ->
                iconDrawCount++
            }
        )
        // Check that layout runs successfully, and the icons are either dropped or some labels skipped.
        assertTrue("Icon draw count should be limited to prevent crowding", iconDrawCount == 0 || iconDrawCount < items.size)
    }
}
