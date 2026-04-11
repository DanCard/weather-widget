package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
class PrecipitationGraphRendererRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `rain amount placed when probability label overlaps vertically`() {
        val start = LocalDateTime.of(2026, 4, 11, 17, 0)
        val probs = listOf(35, 37, 42, 56, 80, 66, 84, 97, 86, 58)
        val hours = probs.mapIndexed { i, prob ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = prob,
                precipAmountMm = if (prob >= 97) 2.1f else if (prob >= 80) 0.085f else null,
                label = formatHour(dt.hour),
                showLabel = true,
            )
        }

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 337,
            currentTime = start.plusHours(4),
            highProbThreshold = 97,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Rain amount label should be placed even when probability labels occupy vertical space. logs=$debugLogs",
            placed.isNotEmpty(),
        )
    }

    @Test
    fun `rain amount blocks do not overlap each other second one skipped on narrow graph`() {
        val start = LocalDateTime.of(2026, 4, 11, 6, 0)
        val probs = listOf(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100)
        val hours = probs.mapIndexed { i, prob ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = prob,
                precipAmountMm = 5.0f,
                label = formatHour(dt.hour),
                showLabel = true,
            )
        }

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 200,
            heightPx = 400,
            currentTime = start.plusHours(6),
            highProbThreshold = 97,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "At least one rain amount label should be placed. logs=$debugLogs",
            placed.isNotEmpty(),
        )
        val secondOverlaps = debugLogs.any { it.startsWith("rainAmountSkipped") && it.contains("overlapsRainAmountLabel=true") }
        assertTrue(
            "On narrow 200px width with 12h of 100%, some rain amount annotations should overlap each other. logs=$debugLogs",
            secondOverlaps || placed.size == 1,
        )
    }

    @Test
    fun `rain amount at 97 percent with highProbThreshold 97`() {
        val start = LocalDateTime.of(2026, 4, 11, 17, 0)
        val probs = listOf(20, 30, 50, 97, 70, 40)
        val hours = probs.mapIndexed { i, prob ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = prob,
                precipAmountMm = if (prob >= 97) 2.1f else null,
                label = formatHour(dt.hour),
                showLabel = true,
            )
        }

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start.plusHours(3),
            highProbThreshold = 97,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "97% block should produce rain amount label with threshold=97. logs=$debugLogs",
            placed.isNotEmpty(),
        )
    }

    @Test
    fun `rain amount not placed at 97 percent with highProbThreshold 99`() {
        val start = LocalDateTime.of(2026, 4, 11, 17, 0)
        val probs = listOf(20, 30, 50, 97, 70, 40)
        val hours = probs.mapIndexed { i, prob ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = prob,
                precipAmountMm = if (prob >= 97) 2.1f else null,
                label = formatHour(dt.hour),
                showLabel = true,
            )
        }

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start.plusHours(3),
            highProbThreshold = 99,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "97% block should NOT produce rain amount label with threshold=99. logs=$debugLogs",
            placed.isEmpty(),
        )
    }

    @Test
    fun `rain amount positioned via grid scan avoiding overlap`() {
        val start = LocalDateTime.of(2026, 4, 11, 17, 0)
        val probs = listOf(20, 30, 97, 30, 20)
        val hours = probs.mapIndexed { i, prob ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = prob,
                precipAmountMm = if (prob >= 97) 5.0f else null,
                label = formatHour(dt.hour),
                showLabel = true,
            )
        }

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start.plusHours(2),
            highProbThreshold = 97,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue("Rain amount should be placed. logs=$debugLogs", placed.isNotEmpty())

        val overlapMatch = Regex("""overlapArea=(\d+\.?\d*)""").find(placed.first())
        assertTrue("Should extract overlapArea from log: ${placed.first()}", overlapMatch != null)
        val overlapArea = overlapMatch!!.groupValues[1].toFloat()
        assertTrue(
            "Rain amount should have zero or minimal overlap. overlapArea=$overlapArea",
            overlapArea < 100f,
        )
    }

    private fun formatHour(hour24: Int): String {
        val h = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "a" else "p"
        return "$h$suffix"
    }
}