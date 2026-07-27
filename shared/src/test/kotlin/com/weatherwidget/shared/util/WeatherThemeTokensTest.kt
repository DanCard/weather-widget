package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Sanity guards on the shared color/spacing tokens. These constants back the desktop's custom
 * Compose color scheme and (eventually) the Android palette; a regression here would silently
 * shift every themed surface.
 */
@Category(ShortDuration::class)
class WeatherThemeTokensTest {

    @Test
    fun everyColorTokenIsOpaqueArgb() {
        val tokens = listOf(
            WeatherThemeTokens.BACKGROUND,
            WeatherThemeTokens.SURFACE,
            WeatherThemeTokens.SURFACE_STROKE,
            WeatherThemeTokens.ON_SURFACE,
            WeatherThemeTokens.ON_SURFACE_SECONDARY,
            WeatherThemeTokens.PRIMARY,
            WeatherThemeTokens.BUTTON_GREEN,
            WeatherThemeTokens.BUTTON_BLUE,
            WeatherThemeTokens.BUTTON_NAVY,
            WeatherThemeTokens.BUTTON_YELLOW,
            WeatherThemeTokens.ON_PRIMARY_DARK,
        )
        for (value in tokens) {
            val alpha = (value shr 24) and 0xFF
            assertTrue(
                "token 0x${value.toString(16)} must be a fully-opaque ARGB value (alpha = 0xFF), got alpha=0x${alpha.toString(16)}",
                alpha == 0xFFL,
            )
        }
    }

    @Test
    fun backgroundIsDarkerThanSurfaceIsDarkerThanStroke() {
        // The Android card aesthetic depends on this tonal ladder — if any layer gets
        // reordered the cards stop reading as raised above the background.
        assertTrue(WeatherThemeTokens.BACKGROUND < WeatherThemeTokens.SURFACE)
        assertTrue(WeatherThemeTokens.SURFACE < WeatherThemeTokens.SURFACE_STROKE)
    }

    @Test
    fun primaryIsIosBlueNotMaterial3StockPurple() {
        // Guard against the "I haven't themed my app yet" default creeping back in. The plan
        // motivation is precisely that the desktop was wearing #BB86FC.
        assertTrue(
            "PRIMARY must be iOS blue (#007AFF), not Material 3's stock #BB86FC",
            WeatherThemeTokens.PRIMARY != 0xFFBB86FC,
        )
        assertTrue(
            "PRIMARY is iOS blue",
            WeatherThemeTokens.PRIMARY == 0xFF007AFF,
        )
    }

    @Test
    fun textColorsAreNotSwappedWithSurfaceColors() {
        // ON_SURFACE must be brighter than the surface it sits on, or the screen is unreadable.
        assertTrue(
            "ON_SURFACE (white) must be brighter than SURFACE (dark grey)",
            WeatherThemeTokens.ON_SURFACE > WeatherThemeTokens.SURFACE,
        )
        assertTrue(
            "ON_SURFACE_SECONDARY (#AAAAAA) must be dimmer than ON_SURFACE (#FFFFFF)",
            WeatherThemeTokens.ON_SURFACE_SECONDARY < WeatherThemeTokens.ON_SURFACE,
        )
    }
}
