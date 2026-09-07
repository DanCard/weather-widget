package com.weatherwidget.shared.graph

/**
 * Pure block selection for the large-Today-column overlay: which text blocks and rows render
 * given the (already settings-gated) content fields. Shared by the Android
 * (`TodayColumnOverlayRenderer`) and desktop (`DailyForecastGraph`) renderers.
 */
object TodayColumnOverlayBlocks {
    data class Row(val text: String, val caption: String? = null)

    data class Block(val key: String, val rows: List<Row>)

    const val KEY_DELTA = "delta"
    const val KEY_DOMINANT_TEMP_AGE = "dominant_temp_age"

    /**
     * Content variants from richest to poorest, feeding [TodayColumnOverlayPlanner]'s degradation
     * ladder: when the full stack will not fit without being drawn across the forecast bars, the
     * planner may fall back to a shorter one.
     *
     * The delta row is never dropped: it is the most informative row, so the poorest variant is
     * "delta only" and the overlay never degrades to nothing while any content is enabled. Variants
     * that collapse to the same block list (because a toggle already removed that row) are folded
     * away, so a caller with only one enabled row gets exactly one variant.
     */
    fun variants(
        deltaValueText: String?,
        deltaCaptionText: String?,
        dominantTempText: String?,
    ): List<List<Block>> =
        listOf(
            build(deltaValueText, deltaCaptionText, dominantTempText),
            build(deltaValueText, deltaCaptionText, null),
        ).filter { it.isNotEmpty() }.distinct()

    fun build(
        deltaValueText: String?,
        deltaCaptionText: String?,
        dominantTempText: String?,
    ): List<Block> =
        listOfNotNull(
            deltaValueText?.takeIf(String::isNotBlank)?.let { value ->
                Block(KEY_DELTA, listOf(Row(value, deltaCaptionText?.takeIf(String::isNotBlank))))
            },
            dominantTempText?.takeIf(String::isNotBlank)?.let {
                Block(KEY_DOMINANT_TEMP_AGE, listOf(Row(it)))
            },
        )
}
