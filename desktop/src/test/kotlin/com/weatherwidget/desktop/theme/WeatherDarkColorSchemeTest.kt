package com.weatherwidget.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.util.WeatherThemeTokens
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the desktop color scheme so it can't silently drift back to Material 3's stock purple or
 * lose the Android palette match. Phase 2 of
 * `plans/260727-desktop-settings-parity-with-android.md`.
 *
 * These tests intentionally inspect the [WeatherDarkColorScheme] / [WeatherTypography] objects
 * rather than mounting a Compose tree — the role-to-token mapping is the contract, and a
 * Compose UI test would only re-derive what these assertions check directly.
 */
@Category(ShortDuration::class)
class WeatherDarkColorSchemeTest {

    @Test
    fun primaryIsIosBlueNotMaterial3StockPurple() {
        assertEquals(
            "primary must be the iOS blue accent, not Material 3's stock #BB86FC",
            Color(WeatherThemeTokens.PRIMARY),
            WeatherDarkColorScheme.primary,
        )
        assertFalse(
            "primary must not equal the Material 3 default purple that this scheme replaces",
            WeatherDarkColorScheme.primary == Color(0xFFBB86FC),
        )
    }

    @Test
    fun backgroundAndSurfaceMatchAndroidCardAesthetic() {
        assertEquals(Color(WeatherThemeTokens.BACKGROUND), WeatherDarkColorScheme.background)
        assertEquals(Color(WeatherThemeTokens.SURFACE), WeatherDarkColorScheme.surface)
        // Surface must be brighter than background so cards read as raised. Compare the packed
        // ARGB longs (Color itself isn't Comparable).
        assertTrue(
            "surface (${WeatherThemeTokens.SURFACE.toString(16)}) must be brighter than background (${WeatherThemeTokens.BACKGROUND.toString(16)})",
            WeatherThemeTokens.SURFACE > WeatherThemeTokens.BACKGROUND,
        )
    }

    @Test
    fun outlineMatchesAndroidCardStroke() {
        // SettingsWindow card borders (Phase 3) and WeatherOutlinedButton both pull `outline` —
        // it has to match Android's surface_card_stroke or the cards won't read as bordered.
        assertEquals(
            Color(WeatherThemeTokens.SURFACE_STROKE),
            WeatherDarkColorScheme.outline,
        )
    }

    @Test
    fun textRolesMatchAndroidPalette() {
        assertEquals(Color(WeatherThemeTokens.ON_SURFACE), WeatherDarkColorScheme.onSurface)
        assertEquals(Color(WeatherThemeTokens.ON_SURFACE), WeatherDarkColorScheme.onBackground)
        assertEquals(
            Color(WeatherThemeTokens.ON_SURFACE_SECONDARY),
            WeatherDarkColorScheme.onSurfaceVariant,
        )
    }

    @Test
    fun typographySizesMatchAndroidActivitySettingsXml() {
        // Sizes mirror the inline android:textSize values listed in WeatherThemeTokens.
        assertEquals(WeatherThemeTokens.TITLE_SP.sp, WeatherTypography.titleLarge.fontSize)
        assertEquals(WeatherThemeTokens.SECTION_HEADER_SP.sp, WeatherTypography.titleMedium.fontSize)
        assertEquals(WeatherThemeTokens.BODY_SP.sp, WeatherTypography.bodyLarge.fontSize)
        assertEquals(WeatherThemeTokens.CAPTION_SP.sp, WeatherTypography.bodyMedium.fontSize)
        assertEquals(WeatherThemeTokens.SMALL_CAP_SP.sp, WeatherTypography.bodySmall.fontSize)
        assertEquals(WeatherThemeTokens.CAPTION_SP.sp, WeatherTypography.labelLarge.fontSize)
    }

    @Test
    fun typographyTitleRolesAreBold() {
        // Android's `settings_title` and section headers are all `textStyle="bold"`.
        assertEquals(
            "titleLarge mirrors Android's bold screen title",
            androidx.compose.ui.text.font.FontWeight.Bold,
            WeatherTypography.titleLarge.fontWeight,
        )
        assertEquals(
            "titleMedium mirrors Android's bold section headers",
            androidx.compose.ui.text.font.FontWeight.Bold,
            WeatherTypography.titleMedium.fontWeight,
        )
    }
}
