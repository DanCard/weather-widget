package com.weatherwidget.widget

import com.weatherwidget.shared.graph.HourlyGraphDefaults

/**
 * Encapsulates the geometry coordinates and bounds of the precipitation graph.
 * Extracted from [PrecipitationGraphRenderer].
 */
data class PrecipGraphGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val graphTop: Float,
    val graphBottom: Float,
    val graphHeight: Float,
    val footerBottomInset: Float,
) {
    companion object {
        fun compute(
            widthPx: Int,
            heightPx: Int,
            labelScale: Float,
            showHourlyIcons: Boolean,
            footerIconSize: Float,
            dpToPx: (Float) -> Float,
            topPaddingDp: Float,
        ): PrecipGraphGeometry {
            val topPadding = dpToPx(topPaddingDp * labelScale)
            val labelHeight = dpToPx(HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)
            val footerBottomInset = dpToPx(HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

            val graphTop = topPadding
            val graphBottom =
                if (showHourlyIcons) {
                    heightPx - footerIconSize - footerBottomInset
                } else {
                    heightPx - labelHeight
                }
            val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
            return PrecipGraphGeometry(
                widthPx = widthPx,
                heightPx = heightPx,
                graphTop = graphTop,
                graphBottom = graphBottom,
                graphHeight = graphHeight,
                footerBottomInset = footerBottomInset,
            )
        }
    }
}
