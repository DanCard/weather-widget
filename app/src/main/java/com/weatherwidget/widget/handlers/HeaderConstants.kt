package com.weatherwidget.widget.handlers

/**
 * Shared constants for the widget header to ensure consistency across all views.
 */
object HeaderConstants {
    const val CURRENT_TEMP_TEXT_SIZE_DP = 18f
    // The daily forecast view runs its header temperature 20% larger than the other views.
    // Used both for the canvas paint (DailyForecastHeaderRenderer) and for the daily header
    // fit math (disclosure / header scale / date placement) so measurement matches what's drawn.
    const val DAILY_CURRENT_TEMP_TEXT_SIZE_DP = CURRENT_TEMP_TEXT_SIZE_DP * 1.2f
    const val WEATHER_ICON_SIZE_DP = 24f
    const val DELTA_TEXT_SIZE_DP = 14f
    // "from yest" caption after the delta: deliberately smaller than the delta itself.
    // Reduced ~20% (10 -> 8) so the opportunistic caption stays out of the way on
    // crowded narrow headers.
    const val DELTA_LABEL_TEXT_SIZE_DP = 8f
    const val WEATHER_ICON_END_MARGIN_DP = 2f
    const val DELTA_MARGIN_START_DP = 4f
    const val DELTA_LABEL_MARGIN_START_DP = 2f
    const val PRECIP_MARGIN_START_DP = 8f
    const val API_SOURCE_MARGIN_END_DP = 32f
    // Extra right margin applied only when the API label is a single source (no " - ")
    // — keeps long dual-source labels at the tight 32dp while giving "Meteo" / "NWS"
    // breathing room from the gear.
    const val API_SINGLE_SOURCE_EXTRA_MARGIN_DP = 12f
    const val API_SOURCE_CONTAINER_PADDING_DP = 14f
    const val DATE_TEXT_SIZE_DP = 20f
    const val DATE_HORIZONTAL_GAP_DP = 6f
    const val DATE_RIGHT_MARGIN_DP = 112f
    const val DATE_MIN_COLUMNS = 6
    const val SETTINGS_ICON_SIZE_DP = 18f
    const val SETTINGS_ICON_MARGIN_END_DP = 0f
    const val PRECIP_TEXT_BASE_SIZE_DP = 18f
    const val CENTER_ICON_SIZE_DP = 20f
    const val GRAPH_SELECTOR_TEXT_SIZE_DP = 16f
    const val API_TEXT_SIZE_LARGE_DP = 12.6f
    const val API_TEXT_SIZE_MEDIUM_DP = 11.2f
    const val API_TEXT_SIZE_SMALL_DP = 9.8f

    fun apiTextSizeDp(numRows: Int): Float = when {
        numRows >= 3 -> API_TEXT_SIZE_LARGE_DP
        numRows >= 2 -> API_TEXT_SIZE_MEDIUM_DP
        else -> API_TEXT_SIZE_SMALL_DP
    }
}
