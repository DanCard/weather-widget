package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureLabelCollisionOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildHours(
        forecastTemps: List<Float>,
        actualTemps: List<Float?>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 4, 8, 0, 0),
    ): List<HourData> =
        forecastTemps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                actualTemperature = actualTemps[index],
                isActual = actualTemps[index] != null,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    @Test
    fun `when two valleys collide the lower temperature should be on the bottom`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        
        // Two valleys close to each other. 
        // Index 10: 52 (Forecast LOW)
        // Index 12: 50 (ACTUAL_LOW)
        // These should collide horizontally.
        val forecast = MutableList(24) { 60f }
        forecast[10] = 52f
        val actual = MutableList<Float?>(24) { null }
        actual[12] = 50f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 300, // Narrow width to force horizontal collision
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val low52 = placements.find { it.temperature == 52f }
        val low50 = placements.find { it.temperature == 50f }

        if (low52 == null || low50 == null) {
            println("Placements for valleys: ${placements.map { "${it.role}=${it.temperature}" }}")
        }

        assertNotNull("Label for 52 should be placed", low52)
        assertNotNull("Label for 50 should be placed", low50)

        // Lower temperature (50) should be on the bottom (larger Y)
        assertTrue(
            "Expected 50° label to be below 52° label. 50_y=${low50!!.y}, 52_y=${low52!!.y}",
            low50.y > low52.y
        )
    }

    @Test
    fun `when two peaks collide the higher temperature should be on top`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        
        // Two peaks close to each other.
        // Index 10: 85 (Forecast HIGH)
        // Index 12: 87 (ACTUAL_HIGH)
        // These should collide horizontally.
        val forecast = MutableList(24) { 70f }
        forecast[10] = 85f
        val actual = MutableList<Float?>(24) { null }
        actual[12] = 87f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 300, // Narrow width to force horizontal collision
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val high85 = placements.find { it.temperature == 85f }
        val high87 = placements.find { it.temperature == 87f }

        if (high85 == null || high87 == null) {
            println("Placements for peaks: ${placements.map { "${it.role}=${it.temperature}" }}")
        }

        assertNotNull("Label for 85 should be placed", high85)
        assertNotNull("Label for 87 should be placed", high87)

        // Higher temperature (87) should be on top (smaller Y)
        assertTrue(
            "Expected 87° label to be above 85° label. 87_y=${high87!!.y}, 85_y=${high85!!.y}",
            high87.y < high85.y
        )
    }

    @Test
    fun `warmer forecast low flips above a heavily overlapping colder actual low`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        // Reproduces the Samsung Fold case: a forecast daily LOW (54) and a colder ACTUAL_LOW
        // (53.2) that land at the same spot, so the below-cascade can't separate them and would
        // otherwise stack them with a heavy vertical overlap.
        //
        // Two test-only geometry notes:
        //  - Robolectric stubs text measurement, so label boxes are only a few px wide. A flat
        //    forecast-low run (idx 24-26) puts the LOW label's centerOfRun on idx 25 — the exact
        //    X of the ACTUAL_LOW point — so the thin boxes still overlap horizontally.
        //  - High of 81 widens the range (~28°) so the 0.8° gap maps to a small Y offset, landing
        //    the vertical overlap in the cascade's heavy-overlap band (~0.7) rather than ≤0.65.
        val forecast = MutableList(48) { 70f }
        forecast[12] = 81f
        forecast[24] = 54f
        forecast[25] = 54f
        forecast[26] = 54f
        val actual = MutableList<Float?>(48) { null }
        actual[25] = 53.2f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 900,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 9, 12, 0),
            observedAt = LocalDateTime.of(2026, 4, 9, 2, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val forecastLow = placements.find { it.temperature == 54f }
        val actualLow = placements.find { it.temperature == 53.2f }

        if (forecastLow == null || actualLow == null) {
            println("Placements: ${placements.map { "${it.role}=${it.temperature} above=${it.placedAbove} y=${"%.1f".format(it.y)} reason=${it.reason}" }}")
        }

        assertNotNull("Forecast LOW (54) should be placed", forecastLow)
        assertNotNull("ACTUAL_LOW (53) should be placed", actualLow)

        // Warmer label (54) lifted above the line; colder label (53) stays below.
        assertTrue(
            "Expected warmer 54° to be placed above. forecastLow=$forecastLow",
            forecastLow!!.placedAbove
        )
        assertTrue(
            "Expected colder 53° to stay below. actualLow=$actualLow",
            !actualLow!!.placedAbove
        )
        // Above-the-line label must have a smaller Y (higher on screen) than the below-the-line one.
        assertTrue(
            "Expected 54° above 53°. 54_y=${forecastLow.y}, 53_y=${actualLow.y}",
            forecastLow.y < actualLow.y
        )
    }

    @Test
    fun `forecast low flips above when it rounds equal but is rawer warmer than actual low`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        // Emulator repro: a forecast daily LOW of 50.0 and an ACTUAL_LOW of 49.8 land at the same
        // spot. Both render as "50°", so a rounded comparison would treat them as equal and stack
        // (or cascade the forecast label down into the hour-axis footer). They are 0.2° apart and a
        // wide range (high 81) compresses that into a near-total vertical overlap (~0.9), heavier
        // than the valley-vs-valley cap. The flip must key off the *raw* temps: 50.0 > 49.8, so the
        // forecast low lifts above the line while the colder actual low stays below.
        val forecast = MutableList(48) { 70f }
        forecast[12] = 81f
        forecast[24] = 50f
        forecast[25] = 50f
        forecast[26] = 50f
        val actual = MutableList<Float?>(48) { null }
        actual[25] = 49.8f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 900,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 9, 12, 0),
            observedAt = LocalDateTime.of(2026, 4, 9, 2, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val forecastLow = placements.find { it.role == TemperatureRole.LOW && it.temperature == 50f }
        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }

        if (forecastLow == null || actualLow == null) {
            println("Placements: ${placements.map { "${it.role}=${it.temperature} above=${it.placedAbove} y=${"%.1f".format(it.y)} reason=${it.reason}" }}")
        }

        assertNotNull("Forecast LOW (50) should be placed", forecastLow)
        assertNotNull("ACTUAL_LOW (49.8) should be placed", actualLow)

        assertTrue(
            "Expected the rawer-warmer 50° forecast low above. forecastLow=$forecastLow",
            forecastLow!!.placedAbove
        )
        assertTrue(
            "Expected the colder 49.8° actual low below. actualLow=$actualLow",
            !actualLow!!.placedAbove
        )
        assertTrue(
            "Expected 50° above 49.8°. 50_y=${forecastLow.y}, 49.8_y=${actualLow.y}",
            forecastLow.y < actualLow.y
        )
    }

    private fun assertNotNull(message: String, value: Any?) {
        if (value == null) throw AssertionError(message)
    }
}
