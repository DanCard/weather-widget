package com.weatherwidget.desktop.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.util.WeatherThemeTokens

/**
 * Compose conversion of the shared [WeatherThemeTokens] palette. This is the desktop's single
 * point of color truth — replacing the stock Material 3 `darkColorScheme()` (whose `#BB86FC`
 * purple was the "I haven't themed my app yet" tell) with the Android app's deliberate Apple-dark
 * palette (`#1C1C1E` / `#2A2A2E` / `#FFFFFF` / etc.).
 *
 * Both clients now read from [WeatherThemeTokens] in `:shared`; this file is the thin adapter
 * that turns the platform-neutral ARGB longs into Compose [ColorScheme] roles.
 *
 * Phase 2 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
val WeatherDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(WeatherThemeTokens.PRIMARY),
    onPrimary = Color(WeatherThemeTokens.ON_SURFACE),
    primaryContainer = Color(WeatherThemeTokens.SURFACE),
    onPrimaryContainer = Color(WeatherThemeTokens.ON_SURFACE),
    inversePrimary = Color(WeatherThemeTokens.BUTTON_BLUE),

    secondary = Color(WeatherThemeTokens.BUTTON_BLUE),
    onSecondary = Color(WeatherThemeTokens.ON_PRIMARY_DARK),
    secondaryContainer = Color(WeatherThemeTokens.SURFACE),
    onSecondaryContainer = Color(WeatherThemeTokens.ON_SURFACE),

    tertiary = Color(WeatherThemeTokens.BUTTON_GREEN),
    onTertiary = Color(WeatherThemeTokens.ON_PRIMARY_DARK),
    tertiaryContainer = Color(WeatherThemeTokens.SURFACE),
    onTertiaryContainer = Color(WeatherThemeTokens.ON_SURFACE),

    background = Color(WeatherThemeTokens.BACKGROUND),
    onBackground = Color(WeatherThemeTokens.ON_SURFACE),

    surface = Color(WeatherThemeTokens.SURFACE),
    onSurface = Color(WeatherThemeTokens.ON_SURFACE),
    surfaceVariant = Color(WeatherThemeTokens.BACKGROUND),
    onSurfaceVariant = Color(WeatherThemeTokens.ON_SURFACE_SECONDARY),
    surfaceTint = Color(WeatherThemeTokens.PRIMARY),
    inverseSurface = Color(WeatherThemeTokens.ON_SURFACE),
    inverseOnSurface = Color(WeatherThemeTokens.BACKGROUND),

    error = Color(0xFFCC4040),
    onError = Color(WeatherThemeTokens.ON_SURFACE),
    errorContainer = Color(0xFF4A2020),
    onErrorContainer = Color(WeatherThemeTokens.ON_SURFACE),

    outline = Color(WeatherThemeTokens.SURFACE_STROKE),
    outlineVariant = Color(WeatherThemeTokens.SURFACE_STROKE),
    scrim = Color(0x99000000),
)

/**
 * Compose [Typography] matching the inline `android:textSize` values used throughout
 * `activity_settings.xml` (18sp title / 16sp section header / 15sp body / 14sp caption / 12sp
 * small caption / 21sp big action button). M3's default type scale was a near miss at every
 * size — titleMedium in particular renders noticeably smaller than the Android 16sp section
 * header, which is why the desktop section titles look weak next to Android.
 *
 * Sizes are sourced from [WeatherThemeTokens]; weights are chosen to match Android's bold-only
 * roles (`titleLarge` is bold on Android, the rest are normal weight).
 */
val WeatherTypography: Typography = Typography(
    // 18sp — Android's `settings_title` (screen title).
    titleLarge = TextStyle(
        fontSize = WeatherThemeTokens.TITLE_SP.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 16sp — Android's section headers (units_title, api_sources_title, …).
    titleMedium = TextStyle(
        fontSize = WeatherThemeTokens.SECTION_HEADER_SP.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 15sp — Android's primary body text and row labels.
    bodyLarge = TextStyle(
        fontSize = WeatherThemeTokens.BODY_SP.sp,
        fontWeight = FontWeight.Normal,
    ),
    // 14sp — Android's description/hint text under a section header.
    bodyMedium = TextStyle(
        fontSize = WeatherThemeTokens.CAPTION_SP.sp,
        fontWeight = FontWeight.Normal,
    ),
    // 12sp — Android's slider endpoints / source_description secondary line.
    bodySmall = TextStyle(
        fontSize = WeatherThemeTokens.SMALL_CAP_SP.sp,
        fontWeight = FontWeight.Normal,
    ),
    // 14sp — Android's header-row button text (`refresh_data_btn`, etc.).
    labelLarge = TextStyle(
        fontSize = WeatherThemeTokens.CAPTION_SP.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // 12sp — Android's small captions.
    labelSmall = TextStyle(
        fontSize = WeatherThemeTokens.SMALL_CAP_SP.sp,
        fontWeight = FontWeight.Normal,
    ),
)
