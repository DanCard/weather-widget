package com.weatherwidget.shared.util

/**
 * Shared visual tokens for the weather settings UI, mirroring the Android
 * `app/src/main/res/values/colors.xml` Apple-dark palette. Pure-JVM so both the
 * Android (View XML) and desktop (Compose) clients can read the same source of
 * truth without duplicating hex literals across modules.
 *
 * Colors are stored as ARGB hex longs (`0xAARRGGBB`) to match the convention of
 * [WeatherColors]. Convert to `Int` at the call site with `.toInt()` (or to a
 * Compose `Color` via `Color(value)`).
 *
 * Phase 1 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`):
 * the desktop's stock Material 3 purple comes from passing `darkColorScheme()`
 * unmodified — these tokens let it build a custom scheme matching Android.
 */
object WeatherThemeTokens {

    // --- Surfaces (from colors.xml: background / surface_card / surface_card_stroke) ---

    /** Root background for all settings screens — `#1C1C1E`. */
    const val BACKGROUND: Long = 0xFF1C1C1E

    /** Card fill — `#2A2A2E`. */
    const val SURFACE: Long = 0xFF2A2A2E

    /** 1dp card border — `#3A3A3E`. */
    const val SURFACE_STROKE: Long = 0xFF3A3A3E

    // --- Text (from colors.xml: widget_text_primary / widget_text_secondary) ---

    /** Primary text, titles, icon tint — `#FFFFFF`. */
    const val ON_SURFACE: Long = 0xFFFFFFFF

    /** Secondary text, descriptions, captions, hints — `#AAAAAA`. */
    const val ON_SURFACE_SECONDARY: Long = 0xFFAAAAAA

    // --- Accents (from colors.xml: primary / accent) ---

    /** iOS blue theme accent — `#007AFF`. Used for section-header text. */
    const val PRIMARY: Long = 0xFF007AFF

    // --- Button palette (from drawable/rounded_button_*.xml) ---

    /** Primary "go" actions: Refresh Data, Set Location — `#4CD964`. */
    const val BUTTON_GREEN: Long = 0xFF4CD964

    /** Secondary actions: View App Logs, View Icon Gallery — `#5AC8FA`. */
    const val BUTTON_BLUE: Long = 0xFF5AC8FA

    /** Tertiary actions: Get API Key, Change App Language — `#0D2B45`. */
    const val BUTTON_NAVY: Long = 0xFF0D2B45

    /** Alert actions: Submit Bug Report — `#FFCC00`. */
    const val BUTTON_YELLOW: Long = 0xFFFFCC00

    /** Dark text rendered on top of [BUTTON_GREEN] / [BUTTON_YELLOW] — `#1A1A1A`. */
    const val ON_PRIMARY_DARK: Long = 0xFF1A1A1A

    // --- Typography scale (sp) — parity reference, applied natively per platform ---

    /** Screen title — `settings_title`. */
    const val TITLE_SP: Int = 18

    /** Section header — `units_title`, `api_sources_title`, etc. */
    const val SECTION_HEADER_SP: Int = 16

    /** Primary body text / row labels. */
    const val BODY_SP: Int = 15

    /** Bold inline emphasis — `personal_station_discount_value`. */
    const val BODY_BOLD_SP: Int = 15

    /** Description / hint under a header. */
    const val CAPTION_SP: Int = 14

    /** Small captions — slider endpoints, source_description. */
    const val SMALL_CAP_SP: Int = 12

    /** Big primary-action button text — Set Location, Submit Bug Report. */
    const val ACTION_BUTTON_SP: Int = 21

    // --- Shape / spacing (dp) ---

    /** Corner radius for cards (`bg_surface_card.xml`). */
    const val CARD_CORNER_DP: Int = 14

    /** Corner radius for color-filled buttons (`rounded_button_*.xml`). */
    const val BUTTON_CORNER_DP: Int = 12

    /** Inner padding for cards. */
    const val CARD_PADDING_DP: Int = 16

    /** Vertical gap between sections (marginTop of each section header). */
    const val SECTION_GAP_DP: Int = 24

    /** Root scroll-view inner padding. */
    const val ROOT_PADDING_DP: Int = 12
}
