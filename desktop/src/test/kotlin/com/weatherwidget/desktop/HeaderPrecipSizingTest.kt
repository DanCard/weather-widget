package com.weatherwidget.desktop

import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Tests for [HeaderPrecipSizing.headerPrecipFontScale] — the desktop port of Android's header
 * rain-chance sizing (probability step table + daily-view-only night shrink).
 */
@Category(ShortDuration::class)
class HeaderPrecipSizingTest {

    @Test
    fun `scale follows the shared probability step table`() {
        assertEquals(0.3f, HeaderPrecipSizing.headerPrecipFontScale(1, isDailyView = true, isNightPrecip = false), 1e-6f)
        assertEquals(0.7f, HeaderPrecipSizing.headerPrecipFontScale(15, isDailyView = true, isNightPrecip = false), 1e-6f)
        assertEquals(0.9f, HeaderPrecipSizing.headerPrecipFontScale(50, isDailyView = true, isNightPrecip = false), 1e-6f)
        assertEquals(1.0f, HeaderPrecipSizing.headerPrecipFontScale(90, isDailyView = true, isNightPrecip = false), 1e-6f)
    }

    @Test
    fun `night precip shrinks only in the daily view`() {
        val expected = DailyRainLabels.precipProbabilityScaleFactor(90) * DailyRainLabels.NIGHT_SCALE
        assertEquals(expected, HeaderPrecipSizing.headerPrecipFontScale(90, isDailyView = true, isNightPrecip = true), 1e-6f)
        // Hourly views never shrink (Android parity: TemperatureStateResolver has no night factor).
        assertEquals(1.0f, HeaderPrecipSizing.headerPrecipFontScale(90, isDailyView = false, isNightPrecip = true), 1e-6f)
        // Daytime rain never shrinks.
        assertEquals(1.0f, HeaderPrecipSizing.headerPrecipFontScale(90, isDailyView = true, isNightPrecip = false), 1e-6f)
    }

    @Test
    fun `full size equals the desktop header temp base`() {
        // Android parity: PRECIP_TEXT_BASE_SIZE_DP == CURRENT_TEMP_TEXT_SIZE_DP there, so a
        // near-certain chance renders at the same size as the header temp here too.
        val fullScale = HeaderPrecipSizing.headerPrecipFontScale(100, isDailyView = false, isNightPrecip = false)
        assertEquals(HeaderPrecipSizing.HEADER_TEMP_BASE_SP, HeaderPrecipSizing.HEADER_TEMP_BASE_SP * fullScale, 1e-6f)
    }
}
