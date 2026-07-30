package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.shared.graph.HourData
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class GraphFailureWatermarkRendererInstrumentedTest {
    @Test
    fun narrowTemperatureGraphFitsLongFailureWatermarkOnRealCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap =
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = emptyList(),
                widthPx = 180,
                heightPx = 120,
                currentTime = LocalDateTime.now(),
                showErrorWatermark = true,
                errorSourceLabel = "Extremely Long Weather Provider",
                errorCode = "HTTP_429",
                errorFailureTimeMs = System.currentTimeMillis(),
                useCelsius = false,
            )

        val hasDrawnPixel =
            (0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) ushr 24 != 0 }
            }
        assertTrue("Expected the narrow failure watermark to draw visible pixels", hasDrawnPixel)
    }

    @Test
    fun narrowTemperatureGraphRendersFooterAndMissingDataContoursOnRealCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val temperatures =
            listOf(60f, 62f, 64f, 66f, 68f, Float.NaN, 72f, 71f, 70f, 69f, 68f, 67f)
        val hours =
            temperatures.mapIndexed { index, temperature ->
                val afterGap = index > 5
                HourData(
                    dateTime = start.plusHours(index.toLong()),
                    temperature = temperature,
                    label = "${index}h",
                    iconRes = if (afterGap) R.drawable.ic_weather_clear else R.drawable.ic_weather_rain,
                    isSunny = afterGap,
                    isRainy = !afterGap,
                    isCurrentHour = index == 0,
                    showLabel = index % 3 == 0,
                )
            }

        val bitmap =
            TemperatureGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = 540,
                heightPx = 420,
                currentTime = start.plusHours(5),
                numColumns = 2,
                useCelsius = false,
            )

        val hasDrawnPixel =
            (0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) ushr 24 != 0 }
            }
        assertTrue("Expected the narrow temperature graph to draw visible pixels", hasDrawnPixel)
    }
}
