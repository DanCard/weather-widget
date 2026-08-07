package com.weatherwidget.shared.graph

/** Cross-platform visual constants; each renderer supplies native text measurement and drawing. */
object TodayColumnOverlayStyle {
    const val TEXT_SIZE_DP = 17f

    /**
     * Android's daily temperature-label size (`DailyGraphLayoutResolver.TEMP_LABEL_TEXT_SIZE_DP`),
     * the reference [TEXT_SIZE_DP] was chosen against.
     */
    const val REFERENCE_TEMP_LABEL_TEXT_SIZE_DP = 24f

    /**
     * Overlay text size as a fraction of the platform's OWN daily temperature-label size (17/24 on
     * Android, so the overlay reads a little smaller than the temperatures beside it).
     *
     * Platforms whose temperature labels are not 24dp must scale by this fraction rather than using
     * [TEXT_SIZE_DP] raw. Desktop's temp labels are 12, so the raw constant rendered the overlay at
     * 1.42x its labels instead of 0.71x — twice the intended relative size, and visibly oversized.
     */
    const val TEXT_SIZE_FRACTION_OF_TEMP_LABEL = TEXT_SIZE_DP / REFERENCE_TEMP_LABEL_TEXT_SIZE_DP
    const val INLINE_CAPTION_TEXT_SCALE = 0.62f
    const val INLINE_CAPTION_GAP_EM = 0.14f
    const val HORIZONTAL_PADDING_DP = 1f
    const val VERTICAL_PADDING_DP = 3f
    const val ROW_SPACING_DP = 1f
    const val MAIN_TEXT_ARGB = 0xFFFFFFFF.toInt()
}
