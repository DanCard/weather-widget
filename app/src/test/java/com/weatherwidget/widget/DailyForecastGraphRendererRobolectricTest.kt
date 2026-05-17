package com.weatherwidget.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import com.weatherwidget.R
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.util.WeatherConditionColors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(MediumDuration::class)
class DailyForecastGraphRendererRobolectricTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `mixed mostly clear day draws grey lower segment`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_mostly_clear,
            cloudCoverRatioOverride = 0.45f,
        )

        assertEquals(
            listOf(WeatherConditionColors.FORECAST_CLOUDY, WeatherConditionColors.FORECAST_SUNNY),
            paintColors,
        )
    }

    @Test
    fun `mixed chance rain day draws blue lower segment`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_partly_cloudy_chance_rain,
            isMixed = true,
            cloudCoverRatioOverride = 0.66f,
        )

        assertEquals(
            listOf(WeatherConditionColors.FORECAST_RAINY, WeatherConditionColors.FORECAST_SUNNY),
            paintColors,
        )
    }

    @Test
    fun `clear icon with cloud override draws grey lower segment`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_clear,
            isMixed = false,
            cloudCoverRatioOverride = 0.29f,
        )

        assertEquals(
            listOf(WeatherConditionColors.FORECAST_CLOUDY, WeatherConditionColors.FORECAST_SUNNY),
            paintColors,
        )
    }

    @Test
    fun `clear icon with zero cloud override draws solid sunny bar`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_clear,
            isMixed = false,
            cloudCoverRatioOverride = 0f,
        )

        assertEquals(listOf(WeatherConditionColors.FORECAST_SUNNY), paintColors)
    }

    private fun capturePrimaryBarPaintColors(
        iconRes: Int,
        isMixed: Boolean = true,
        cloudCoverRatioOverride: Float,
    ): List<Int> {
        val context = mockContext()
        val paintSlot = slot<Paint>()
        val paintColors = mutableListOf<Int>()
        every { anyConstructed<Canvas>().drawLine(any(), any(), any(), any(), capture(paintSlot)) } answers {
            paintColors.add(paintSlot.captured.color)
            Unit
        }

        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = listOf(
                    DailyForecastGraphRenderer.DayData(
                        date = LocalDate.of(2026, 4, 19),
                        label = "Sat",
                        solidLineHigh = 76f,
                        solidLineLow = 46f,
                        iconRes = iconRes,
                        isSunny = true,
                        isMixed = isMixed,
                        cloudCoverRatioOverride = cloudCoverRatioOverride,
                    ),
                ),
                widthPx = 240,
                heightPx = 360,
            )
        }

        return paintColors
    }

    private fun mockContext(): Context {
        mockkStatic(Bitmap::class)
        mockkConstructor(Canvas::class)

        val bitmap = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } returns bitmap
        every { anyConstructed<Canvas>().drawText(any<String>(), any(), any(), any()) } returns Unit
        every { anyConstructed<Canvas>().drawLine(any(), any(), any(), any(), any()) } returns Unit
        every { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) } returns Unit

        val metrics = DisplayMetrics().apply { density = 1.0f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        return context
    }
}
