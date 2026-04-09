package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    private fun assertNotNull(message: String, value: Any?) {
        if (value == null) throw AssertionError(message)
    }
}
