package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class DailyForecastGraphRendererSizingTest {

    @Test
    fun `day label width scale shrinks only slightly on tight columns`() {
        val scale = DailyForecastGraphRenderer.computeDayLabelWidthScale(dayWidthDp = 60f)

        assertEquals(0.96f, scale, 0.0001f)
    }

    @Test
    fun `day label width scale stays at baseline on standard columns`() {
        val scale = DailyForecastGraphRenderer.computeDayLabelWidthScale(dayWidthDp = 70f)

        assertEquals(1.0f, scale, 0.0001f)
    }

    @Test
    fun `forecast temperature label size uses larger daily baseline`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyForecastGraphRenderer.dailyForecastTempLabelSizePx(context)

        assertEquals(20f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `forecast temperature label size compensates for bitmap downscale`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyForecastGraphRenderer.dailyForecastTempLabelSizePx(context, bitmapScale = 0.34f)

        assertEquals(10f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `daily bar stroke uses wider baseline and bitmap scale floor`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val fullScale = DailyForecastGraphRenderer.dailyBarStrokeWidthPx(context)
        val downscaled = DailyForecastGraphRenderer.dailyBarStrokeWidthPx(context, bitmapScale = 0.34f)

        assertEquals(6.5f * context.resources.displayMetrics.density, fullScale, 0.01f)
        assertEquals(3.25f * context.resources.displayMetrics.density, downscaled, 0.01f)
    }

    @Test
    fun `today column bar stroke uses wider baseline`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val fullScale = DailyForecastGraphRenderer.todayTripleBarStrokeWidthPx(context)
        val downscaled = DailyForecastGraphRenderer.todayTripleBarStrokeWidthPx(context, bitmapScale = 0.34f)

        assertEquals(5.25f * context.resources.displayMetrics.density, fullScale, 0.01f)
        assertEquals(2.625f * context.resources.displayMetrics.density, downscaled, 0.01f)
    }
}
