package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RateLimitedWatermarkRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val drawnTexts = mutableListOf<String>()

    @Before
    fun setUp() {
        drawnTexts.clear()
        mockkConstructor(Canvas::class)
        val textSlot = slot<String>()
        every {
            anyConstructed<Canvas>().drawText(capture(textSlot), any(), any(), any())
        } answers {
            drawnTexts.add(textSlot.captured)
            Unit
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `DailyForecastGraphRenderer draws watermark when rate limited`() {
        // Test with empty days
        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = emptyList(),
            widthPx = 300,
            heightPx = 200,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test with non-empty days
        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.now(),
                    label = "Today",
                    solidLineHigh = 75f,
                    solidLineLow = 55f,
                    iconRes = com.weatherwidget.R.drawable.ic_weather_clear
                )
            ),
            widthPx = 300,
            heightPx = 200,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test showErrorWatermark = false does not draw watermark
        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = listOf(
                DailyForecastGraphRenderer.DayData(
                    date = LocalDate.now(),
                    label = "Today",
                    solidLineHigh = 75f,
                    solidLineLow = 55f,
                    iconRes = com.weatherwidget.R.drawable.ic_weather_clear
                )
            ),
            widthPx = 300,
            heightPx = 200,
            showErrorWatermark = false
        )
        assertTrue(drawnTexts.none { it.contains("UPDATES FAILING") })
    }

    @Test
    fun `TemperatureGraphRenderer draws watermark when rate limited`() {
        val now = LocalDateTime.now()
        // Test with empty hours
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = emptyList(),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test with non-empty hours
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                HourData(
                    dateTime = now,
                    temperature = 72f,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test showErrorWatermark = false does not draw watermark
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                HourData(
                    dateTime = now,
                    temperature = 72f,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = false
        )
        assertTrue(drawnTexts.none { it.contains("UPDATES FAILING") })
    }

    @Test
    fun `PrecipitationGraphRenderer draws watermark when rate limited`() {
        val now = LocalDateTime.now()
        // Test with empty hours
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = emptyList(),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test with non-empty hours
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = now,
                    precipProbability = 40,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test showErrorWatermark = false does not draw watermark
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = now,
                    precipProbability = 40,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = false
        )
        assertTrue(drawnTexts.none { it.contains("UPDATES FAILING") })
    }

    @Test
    fun `CloudCoverGraphRenderer draws watermark when rate limited`() {
        val now = LocalDateTime.now()
        // Test with empty hours
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = emptyList(),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test with non-empty hours
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                CloudCoverGraphRenderer.CloudHourData(
                    dateTime = now,
                    cloudCover = 80,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = true
        )
        assertTrue(drawnTexts.any { it.contains("UPDATES FAILING") })

        drawnTexts.clear()

        // Test showErrorWatermark = false does not draw watermark
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = listOf(
                CloudCoverGraphRenderer.CloudHourData(
                    dateTime = now,
                    cloudCover = 80,
                    label = "12p"
                )
            ),
            widthPx = 300,
            heightPx = 200,
            currentTime = now,
            showErrorWatermark = false
        )
        assertTrue(drawnTexts.none { it.contains("UPDATES FAILING") })
    }
}
