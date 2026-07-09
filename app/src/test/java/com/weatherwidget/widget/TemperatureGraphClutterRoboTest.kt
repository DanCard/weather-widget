package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TemperatureGraphClutterRoboTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun buildHours(
        temps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 2, 17, 19, 0),
    ): List<HourData> {
        return temps.mapIndexed { index, temp ->
            val dt = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dt,
                temperature = temp,
                label = "${dt.hour}",
                isCurrentHour = false,
                showLabel = index % 4 == 0,
            )
        }
    }

    @Test
    fun minorHump_oneDegree_isNotLabeled() {
        val temps = listOf(55f, 56f, 58f, 60f, 60f, 61f, 60f, 60f, 58f, 56f, 54f)
        val hours = buildHours(temps)
        val placements = mutableListOf<LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }, useCelsius = false
        )

        assertFalse("Expected minor 1° hump NOT to be drawn", placements.any { it.role == TemperatureRole.LOCAL && it.index == 5 })
    }
}