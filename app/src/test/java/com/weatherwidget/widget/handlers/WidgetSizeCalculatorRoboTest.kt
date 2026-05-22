package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetSizeCalculatorRoboTest {
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
        val maxPixels = 225000

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
        val smallWidthDp = 100
        val smallHeightDp = 100

        val rawWidth = WidgetSizeCalculator.dpToPx(context, smallWidthDp)
        val rawHeight = WidgetSizeCalculator.dpToPx(context, smallHeightDp)
        val rawPixels = rawWidth * rawHeight

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