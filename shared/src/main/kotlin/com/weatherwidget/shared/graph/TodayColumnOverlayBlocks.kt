package com.weatherwidget.shared.graph

/**
 * Pure block selection for the large-Today-column overlay: which text blocks and rows render
 * given the (already settings-gated) content fields. Shared by the Android
 * (`TodayColumnOverlayRenderer`) and desktop (`DailyForecastGraph`) renderers so the independent
 * temp/age toggles behave identically on both platforms — e.g. the reading age can render alone
 * when the station temperature is toggled off.
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
     * Rows are dropped least-informative first — the reading age, then the station temperature. The
     * delta row is never dropped: it is the most informative of the three, so the poorest variant is
     * "delta only" and the overlay never degrades to nothing while any content is enabled. Variants
     * that collapse to the same block list (because a toggle already removed that row) are folded
     * away, so a caller with only one enabled row gets exactly one variant.
     */
    fun variants(
        deltaValueText: String?,
        deltaCaptionText: String?,
        dominantTempText: String?,
        dominantAgeText: String?,
    ): List<List<Block>> =
        listOf(
            build(deltaValueText, deltaCaptionText, dominantTempText, dominantAgeText),
            build(deltaValueText, deltaCaptionText, dominantTempText, null),
            build(deltaValueText, deltaCaptionText, null, null),
        ).filter { it.isNotEmpty() }.distinct()

    fun build(
        deltaValueText: String?,
        deltaCaptionText: String?,
        dominantTempText: String?,
        dominantAgeText: String?,
    ): List<Block> =
        listOfNotNull(
            deltaValueText?.takeIf(String::isNotBlank)?.let { value ->
                Block(KEY_DELTA, listOf(Row(value, deltaCaptionText?.takeIf(String::isNotBlank))))
            },
            // Temp and age are independently togglable: emit the block when either row survives.
            listOfNotNull(
                dominantTempText?.takeIf(String::isNotBlank),
                dominantAgeText?.takeIf(String::isNotBlank),
            ).takeIf { it.isNotEmpty() }?.let { rows ->
                Block(KEY_DOMINANT_TEMP_AGE, rows.map { Row(it) })
            },
        )
}
