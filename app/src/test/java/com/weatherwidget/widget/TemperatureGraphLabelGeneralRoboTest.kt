package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
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
@Config(sdk = [34])
class TemperatureGraphLabelGeneralRoboTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun verifyLocalMinLabelIsDrawn() {
        val now = LocalDateTime.of(2026, 2, 17, 4, 0)

        val hours = listOf(
            createHour(now.plusHours(0), 46.0f),
            createHour(now.plusHours(1), 45.0f),
            createHour(now.plusHours(2), 45.0f),
            createHour(now.plusHours(3), 44.0f),
            createHour(now.plusHours(4), 44.0f),
            createHour(now.plusHours(5), 44.0f),
            createHour(now.plusHours(6), 44.0f),
            createHour(now.plusHours(7), 46.0f),
            createHour(now.plusHours(8), 47.0f)
        )

        val drawnLabels = mutableListOf<LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1080,
            heightPx = 400,
            currentTime = now,
            onLabelPlaced = { drawnLabels.add(it) }
        )

        val drawn44 = drawnLabels.any { it.temperature.toInt() == 44 }
        assertTrue("Local minimum (44) should be drawn. Actual: $drawnLabels", drawn44)
    }

    private fun createHour(time: LocalDateTime, temp: Float): HourData {
        return HourData(
            dateTime = time,
            temperature = temp,
            label = "${time.hour}h"
        )
    }
}