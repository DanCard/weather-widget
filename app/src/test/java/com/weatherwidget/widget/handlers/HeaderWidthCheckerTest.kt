package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class HeaderWidthCheckerTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

        @Test
        fun `computeHeaderScale returns 1-0 for Pixel 7 Pro standard width`() {
        // Pixel 7 Pro standard width is ~411dp. 
        // It should NOT scale up even if empty space is high, to avoid "Everything enlarged" bug.
        val scale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = 411,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        assertEquals(1.0f, scale, 0.01f)
        }

        @Test
        fun `computeHeaderScale returns 1-35 for very wide Samsung-like width with low occupancy`() {
        // Very wide widget (e.g. 500dp) should scale up if occupancy < 0.50
        val scale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        // At 500dp width, occupied width is ~164dp (occupancy ~0.33), which is < 0.50.
        // It meets both the width requirement (>=450) and occupancy requirement.
        assertEquals(1.35f, scale, 0.01f)
        }
}
