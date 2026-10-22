package com.weatherwidget.widget.handlers

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header rain % never yields to the date (user, 2026-10-22): it shows whenever the disclosure
 * ladder keeps it, and the painted date drops itself instead
 * (`DailyForecastHeaderDateSlotTest`, "dropped when neither position fits").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyHeaderPrecipPlacementTest {

    @Test
    fun `rain percent shows whenever the disclosure ladder keeps it`() {
        // The old rule hid it here whenever that let the date fit; there is no date input any more.
        val placement = DailyHeaderBinder.resolveHeaderPrecipPlacement(
            precipText = "100%",
            precipTextSizeDp = 26f,
            headerCanShowPrecip = true,
        )
        assertTrue(placement.showHeaderPrecip)
        assertFalse(placement.allowTodayColumnPrecip)
    }

    @Test
    fun `rain percent hidden when the disclosure ladder drops it`() {
        assertFalse(
            DailyHeaderBinder.resolveHeaderPrecipPlacement("100%", 26f, headerCanShowPrecip = false).showHeaderPrecip,
        )
    }

    @Test
    fun `nothing to show without rain text or size`() {
        assertFalse(DailyHeaderBinder.resolveHeaderPrecipPlacement(null, 26f, headerCanShowPrecip = true).showHeaderPrecip)
        assertFalse(DailyHeaderBinder.resolveHeaderPrecipPlacement("40%", null, headerCanShowPrecip = true).showHeaderPrecip)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `current temp text size ignores font scale`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val metrics = context.resources.displayMetrics
        val originalScaledDensity = metrics.scaledDensity
        val expectedPx = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP * metrics.density

        try {
            metrics.scaledDensity = metrics.density * 0.8f
            val samsungScaleSize = DailyHeaderBinder.currentTempTextSizePx(context)

            metrics.scaledDensity = metrics.density * 1.15f
            val pixelScaleSize = DailyHeaderBinder.currentTempTextSizePx(context)

            assertEquals(expectedPx, samsungScaleSize, 0.01f)
            assertEquals(expectedPx, pixelScaleSize, 0.01f)
        } finally {
            metrics.scaledDensity = originalScaledDensity
        }
    }
}
