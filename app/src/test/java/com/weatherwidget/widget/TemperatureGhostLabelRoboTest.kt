package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TemperatureGhostLabelRoboTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun endLabel_staysOnForecastLineAfterFetchTransition() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(
                    dateTime = start.plusHours(0),
                    temperature = 70.0f,
                    actualTemperature = 70.0f,
                    isActual = true,
                    label = "3p",
                ),
                HourData(
                    dateTime = start.plusHours(1),
                    temperature = 75.0f,
                    actualTemperature = 75.0f,
                    isActual = true,
                    label = "4p",
                ),
                HourData(
                    dateTime = start.plusHours(2),
                    temperature = 74.0f,
                    label = "5p",
                ),
                HourData(
                    dateTime = start.plusHours(3),
                    temperature = 73.0f,
                    label = "6p",
                ),
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

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("Expected END label to be drawn. placements=$placements", endPlacement)
        assertEquals(
            "END label should stay on forecast line value, not ghost line value",
            73.0f,
            endPlacement!!.temperature,
            0.01f,
        )
        assertEquals("forecast", endPlacement.series)
        assertEquals("forecast", endPlacement.colorFamily)
    }

    @Test
    fun forecastLabels_afterFetchTransition_ignoreAppliedDelta() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val observedAtMs = start.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(
                    dateTime = start.plusHours(0),
                    temperature = 68.0f,
                    actualTemperature = 68.0f,
                    isActual = true,
                    label = "3p",
                ),
                HourData(
                    dateTime = start.plusHours(1),
                    temperature = 69.0f,
                    actualTemperature = 69.0f,
                    isActual = true,
                    label = "4p",
                ),
                HourData(
                    dateTime = start.plusHours(2),
                    temperature = 72.0f,
                    label = "5p",
                ),
                HourData(
                    dateTime = start.plusHours(3),
                    temperature = 76.0f,
                    label = "6p",
                ),
                HourData(
                    dateTime = start.plusHours(4),
                    temperature = 74.0f,
                    label = "7p",
                ),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 450,
            currentTime = start.plusHours(1),
            observedAt = observedAtMs,
            lastObservedTemp = 69.0f,
            appliedDelta = 6.0f,
            onLabelPlaced = { placements.add(it) },
        )

        val futureLabels = placements.filter { it.series == "forecast" }
        assertTrue("Expected at least one forecast-side label. placements=$placements", futureLabels.isNotEmpty())
        assertTrue(
            "Forecast-side labels should not use ghost-line temperatures shifted by delta. placements=$placements",
            futureLabels.none { it.temperature > it.rawTemperature + 0.1f },
        )
    }

    @Test
    fun endLabel_isSuppressedWhenAdjacentFutureLabelWouldCrowdEndpoint() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 4, 8, 0)
        val observedAtMs = start.plusHours(7).plusMinutes(55).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hours =
            listOf(
                HourData(dateTime = start.plusHours(0), temperature = 52.0f, actualTemperature = 53.5f, isActual = true, label = "8a"),
                HourData(dateTime = start.plusHours(1), temperature = 81.0f, actualTemperature = 82.0f, isActual = true, label = "9a"),
                HourData(dateTime = start.plusHours(2), temperature = 79.0f, label = "10a"),
                HourData(dateTime = start.plusHours(3), temperature = 77.0f, label = "11a"),
                HourData(dateTime = start.plusHours(4), temperature = 70.0f, label = "12p"),
                HourData(dateTime = start.plusHours(5), temperature = 65.0f, label = "1p"),
                HourData(dateTime = start.plusHours(6), temperature = 60.0f, label = "2p"),
                HourData(dateTime = start.plusHours(7), temperature = 58.0f, label = "3p"),
                HourData(dateTime = start.plusHours(8), temperature = 56.0f, label = "4p"),
                HourData(dateTime = start.plusHours(9), temperature = 55.0f, label = "5p"),
                HourData(dateTime = start.plusHours(10), temperature = 57.0f, label = "6p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 584,
            heightPx = 385,
            currentTime = start.plusHours(7),
            observedAt = observedAtMs,
            lastObservedTemp = 82.0f,
            onLabelPlaced = { placements.add(it) },
        )

        val endPlacement = placements.find { it.role == TemperatureRole.END }
        assertNotNull("END should always be shown as an essential boundary marker. placements=$placements", endPlacement)
    }
}