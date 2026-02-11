package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WidgetSizeCalculator.
 */
@RunWith(AndroidJUnit4::class)
class WidgetSizeCalculatorTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun dpToPx_convertsCorrectly() {
        val density = context.resources.displayMetrics.density
        val dp = 100
        val expectedPx = (dp * density).toInt()

        val result = WidgetSizeCalculator.dpToPx(context, dp)

        assertEquals(expectedPx, result)
    }

    @Test
    fun getOptimalBitmapSize_downscalesLargeBitmaps() {
        val maxPixels = 225000 // MAX_BITMAP_PIXELS

        // Use large dp values that will exceed MAX_BITMAP_PIXELS when converted to pixels
        // At density ~2.6, 500dp = ~1300px, 1300*1300 = 1.69M pixels > 225K
        val largeWidthDp = 500
        val largeHeightDp = 500

        val (resultWidth, resultHeight) =
            WidgetSizeCalculator.getOptimalBitmapSize(
                context,
                largeWidthDp,
                largeHeightDp,
            )

        val rawWidth = WidgetSizeCalculator.dpToPx(context, largeWidthDp)
        val rawHeight = WidgetSizeCalculator.dpToPx(context, largeHeightDp)

        assertTrue("Width should be downscaled", resultWidth < rawWidth)
        assertTrue("Height should be downscaled", resultHeight < rawHeight)
        assertTrue("Result should be under max pixels", resultWidth * resultHeight <= maxPixels)
    }

    @Test
    fun getOptimalBitmapSize_preservesSmallBitmaps() {
        // Small dp values that won't exceed MAX_BITMAP_PIXELS
        // At any density, 100dp x 100dp = 10000 dp² which converts to well under 225K pixels
        val smallWidthDp = 100
        val smallHeightDp = 100

        val rawWidth = WidgetSizeCalculator.dpToPx(context, smallWidthDp)
        val rawHeight = WidgetSizeCalculator.dpToPx(context, smallHeightDp)
        val rawPixels = rawWidth * rawHeight

        // This should be under the threshold
        if (rawPixels <= 225000) {
            val (resultWidth, resultHeight) =
                WidgetSizeCalculator.getOptimalBitmapSize(
                    context,
                    smallWidthDp,
                    smallHeightDp,
                )

            assertEquals(rawWidth, resultWidth)
            assertEquals(rawHeight, resultHeight)
        }
    }
}
