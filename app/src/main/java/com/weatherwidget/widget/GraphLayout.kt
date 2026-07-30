package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.HourlyGraphDefaults

object GraphLayout {
    private const val TAG = "TempGraphRenderer"
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 2.5f
    private const val GRAPH_TOP_PADDING_DP = 16f
    private const val GRAPH_TO_FOOTER_GAP_DP = -1f
    private const val DEFAULT_FALLBACK_MIN_TEMP = 0f
    private const val DEFAULT_FALLBACK_MAX_TEMP = 100f
    private const val MIN_TEMP_RANGE = 1f
    private const val MIN_GRAPH_HEIGHT_DP = 1f

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
        val allTemps = hours.map { it.temperature }.filter { !it.isNaN() } + hours.mapNotNull { it.actualTemperature }
        val rawMin = allTemps.minOrNull() ?: DEFAULT_FALLBACK_MIN_TEMP
        val rawMax = allTemps.maxOrNull() ?: DEFAULT_FALLBACK_MAX_TEMP
        val rawRange = (rawMax - rawMin).coerceAtLeast(MIN_TEMP_RANGE)

        val topBuffer = (rawRange * TOP_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_TOP_TEMP_BUFFER_DEGREES)
        val bottomBuffer = (rawRange * BOTTOM_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_BOTTOM_TEMP_BUFFER_DEGREES)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val tempRange = (maxTemp - minTemp).coerceAtLeast(MIN_TEMP_RANGE)
        Log.v(TAG, "Scaling: rawMin=$rawMin, rawMax=$rawMax, minTemp=$minTemp, maxTemp=$maxTemp, tempRange=$tempRange")
        return Triple(minTemp, maxTemp, tempRange)
    }

    // The footer is now a single inline row (<hour><a|p><icon>) sized by [footerIconSize], the
    // tallest element. Reserving just that one row (vs. the old stacked icon + label rows) hands
    // the reclaimed vertical space back to the graph curve.
    fun computeLayout(context: Context, heightPx: Int, labelScale: Float, footerIconSize: Float): Layout {
        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP * labelScale)
        val bottomInset = dpToPx(context, HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

        val graphTop = topPadding
        val footerTop = heightPx - footerIconSize - bottomInset
        val graphBottom = (footerTop - dpToPx(context, GRAPH_TO_FOOTER_GAP_DP * labelScale)).coerceAtLeast(graphTop + MIN_GRAPH_HEIGHT_DP * labelScale)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(MIN_GRAPH_HEIGHT_DP * labelScale)
        Log.v(TAG, "Layout: heightPx=$heightPx, footerTop=$footerTop, graphTop=$graphTop, graphBottom=$graphBottom, graphHeight=$graphHeight")
        return Layout(topPadding, footerIconSize.toInt(), footerTop, graphTop, graphBottom, graphHeight, iconTopPad = 0f)
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
