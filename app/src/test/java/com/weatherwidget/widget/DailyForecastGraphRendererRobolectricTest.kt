package com.weatherwidget.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
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

@Category(LongDuration::class)
class DailyForecastGraphRendererRobolectricTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `mixed mostly clear day draws split bar`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_mostly_clear,
            cloudCoverRatioOverride = 0.45f,
        )
        // The segment colours are asserted in WeatherConditionColorsTest (real shared constants);
        // here, in plain JUnit with Paint stubbed to 0, only the split-vs-solid structure is
        // observable: a mixed bar emits two drawLine calls (grey bottom + sunny top).
        assertEquals(2, paintColors.size)
    }

    @Test
    fun `mixed chance rain day draws split bar`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_partly_cloudy_chance_rain,
            isMixed = true,
            cloudCoverRatioOverride = 0.66f,
        )
        // Blue bottom is asserted in WeatherConditionColorsTest; here only the split structure.
        assertEquals(2, paintColors.size)
    }

    @Test
    fun `clear icon with cloud override draws split bar`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_clear,
            isMixed = false,
            cloudCoverRatioOverride = 0.29f,
        )
        // Grey bottom is asserted in WeatherConditionColorsTest; here only the split structure.
        assertEquals(2, paintColors.size)
    }

    @Test
    fun `clear icon with zero cloud override draws solid bar`() {
        val paintColors = capturePrimaryBarPaintColors(
            iconRes = R.drawable.ic_weather_clear,
            isMixed = false,
            cloudCoverRatioOverride = 0f,
        )
        // Solid sunny colour is asserted in WeatherConditionColorsTest; here only the solid
        // structure (a single drawLine call, no split).
        assertEquals(1, paintColors.size)
    }

    /**
     * Regression test for the today column's snapshot bar (yesterday's forecast for today —
     * the bright-yellow bar to the RIGHT of the thermostat). It must carry the same grey
     * cloud-cover segment as the live-forecast bar. Previously [drawTodayTripleBar] forced
     * `cloudCoverRatioOverride = null` on the snapshot copy, so a sunny snapshot icon produced
     * a solid yellow bar with no grey bottom.
     *
     * A weather-adaptive (grey-segmented) bar emits TWO drawLine calls at its X (grey full
     * height + colored top); a solid bar emits ONE. The snapshot bar is drawn at
     * centerX + tripleBarOffset — the rightmost of the three today bars. (Colors can't be
     * asserted here: this is plain JUnit, so android Color/Paint return 0.)
     */
    @Test
    fun `today snapshot bar draws grey cloud segment when cloud cover present`() {
        val lineCountsByX = captureTodayBarLineCountsByX(
            snapshotIconRes = R.drawable.ic_weather_clear,
            cloudCoverRatioOverride = 0.36f,
        )

        val snapshotBarX = lineCountsByX.keys.max() // rightmost bar = snapshot
        assertEquals(
            "snapshot bar should be split into grey + colored segments (2 drawLine calls)",
            2,
            lineCountsByX[snapshotBarX],
        )
    }

    @Test
    fun `today snapshot bar stays solid when no cloud cover data`() {
        val lineCountsByX = captureTodayBarLineCountsByX(
            snapshotIconRes = R.drawable.ic_weather_clear,
            cloudCoverRatioOverride = null,
        )

        // No cloud data + sunny snapshot icon → the snapshot bar is a single solid line.
        val snapshotBarX = lineCountsByX.keys.max()
        assertEquals(1, lineCountsByX[snapshotBarX])
    }

    /**
     * Renders a single TODAY column and returns the count of drawLine calls at each X position.
     * The three bars land at distinct X: live-forecast (centerX − offset), observed thermostat
     * (centerX), and snapshot (centerX + offset, the rightmost).
     */
    private fun captureTodayBarLineCountsByX(
        snapshotIconRes: Int,
        cloudCoverRatioOverride: Float?,
    ): Map<Float, Int> {
        val context = mockContext()
        val xSlot = slot<Float>()
        val lineXs = mutableListOf<Float>()
        every {
            anyConstructed<Canvas>().drawLine(capture(xSlot), any(), any(), any(), any())
        } answers {
            lineXs.add(xSlot.captured)
            Unit
        }

        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = listOf(
                    DailyForecastGraphRenderer.DayData(
                        date = LocalDate.of(2026, 5, 25),
                        label = "Today",
                        // Observed thermostat (solid), today's live forecast (dashed),
                        // and yesterday's forecast for today (snapshot).
                        solidLineHigh = 68f,
                        solidLineLow = 50f,
                        dashedLineHigh = 70f,
                        dashedLineLow = 52f,
                        snapshotHigh = 72f,
                        snapshotLow = 50f,
                        snapshotIconRes = snapshotIconRes,
                        iconRes = R.drawable.ic_weather_clear,
                        isSunny = true,
                        isMixed = false,
                        isToday = true,
                        cloudCoverRatioOverride = cloudCoverRatioOverride,
                    ),
                ),
                widthPx = 240,
                heightPx = 360, useCelsius = false,
            )
        }

        return lineXs.groupingBy { it }.eachCount()
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
                heightPx = 360, useCelsius = false,
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
