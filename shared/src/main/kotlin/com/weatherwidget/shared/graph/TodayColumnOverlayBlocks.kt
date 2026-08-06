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
