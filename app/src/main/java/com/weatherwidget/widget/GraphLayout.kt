package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import android.util.TypedValue

object GraphLayout {
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 2.5f
    private const val GRAPH_TOP_PADDING_DP = 8f
    private const val GRAPH_TO_FOOTER_GAP_DP = -1f

    data class Layout(
        val topPadding: Float,
        val iconSize: Int,
        val footerTop: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val iconTopPad: Float,
    )

    fun computeScaling(hours: List<HourData>): Triple<Float, Float, Float> {
        val allTemps = hours.map { it.temperature } + hours.mapNotNull { it.actualTemperature }
        val rawMin = allTemps.minOrNull() ?: 0f
        val rawMax = allTemps.maxOrNull() ?: 100f
        val rawRange = (rawMax - rawMin).coerceAtLeast(1f)

        val topBuffer = (rawRange * TOP_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_TOP_TEMP_BUFFER_DEGREES)
        val bottomBuffer = (rawRange * BOTTOM_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_BOTTOM_TEMP_BUFFER_DEGREES)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)
        Log.d("TempGraphRenderer", "Scaling: rawMin=$rawMin, rawMax=$rawMax, minTemp=$minTemp, maxTemp=$maxTemp, tempRange=$tempRange")
        return Triple(minTemp, maxTemp, tempRange)
    }

    fun computeLayout(context: Context, heightPx: Int, labelScale: Float): Layout {
        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP)
        val iconSize = dpToPx(context, 15f).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, -1f)
        val iconBottomPad = dpToPx(context, 0f)

        val graphTop = topPadding
        val footerTop = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphBottom = (footerTop - dpToPx(context, GRAPH_TO_FOOTER_GAP_DP)).coerceAtLeast(graphTop + 1f)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        Log.d("TempGraphRenderer", "Layout: heightPx=$heightPx, footerTop=$footerTop, graphTop=$graphTop, graphBottom=$graphBottom, graphHeight=$graphHeight")
        return Layout(topPadding, iconSize, footerTop, graphTop, graphBottom, graphHeight, iconTopPad)
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
