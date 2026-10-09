package com.weatherwidget.shared.graph

/**
 * The palette of the data screens — Current Observations and History of Forecasts — on Android and
 * desktop (user, 2026-10-09: History of Forecasts takes the Observations look). `#RRGGBB` strings;
 * each platform parses them into its own color type.
 */
object DataScreenStyle {
    const val BACKGROUND = "#000000"
    const val CARD_FILL = "#121214"
    const val CARD_BORDER = "#2A2A2E"
    const val CARD_RADIUS_DP = 12f
    const val CARD_BORDER_DP = 1f
    const val TEXT_PRIMARY = "#FFFFFF"
    const val TEXT_SECONDARY = "#AAAAAA"
    const val ACCENT = "#4FC3F7"

    /** The navy pill behind the API source button; its text is [ACCENT]. */
    const val SOURCE_BUTTON_FILL = "#0D2B45"
    const val DIVIDER = "#222226"
}
