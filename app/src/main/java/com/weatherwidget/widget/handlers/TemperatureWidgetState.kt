package com.weatherwidget.widget.handlers

import android.graphics.Bitmap
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.HourData
import com.weatherwidget.widget.ZoomLevel

internal data class TemperatureWidgetState(
    val appWidgetId: Int,
    val numRows: Int,
    val widthDp: Int,
    val header: HeaderState,
    val graph: GraphState,
    val warning: SourceWarningState?,
    val displaySource: WeatherSource,
    val zoom: ZoomLevel,
    val hourlyOffset: Int,
) {
    data class HeaderState(
        val sourceIndicator: String,
        val iconRes: Int,
        val currentTemp: String?,
        val currentTempSizeSp: Float,
        val deltaText: String?,
        val deltaColor: Int,
        val precipProbability: String?,
        val precipTextSizeSp: Float,
        val isPrecipVisible: Boolean,
        val isCurrentTempVisible: Boolean,
        val isDeltaVisible: Boolean,
        val isStaleEstimate: Boolean,
    )

    data class GraphState(
        val useGraph: Boolean,
        val bitmap: Bitmap?,
        val hourData: List<HourData>,
        val showTextMode: Boolean,
    )

    data class SourceWarningState(
        val warning: ApiSourceWarningHelper.SourceWarning,
    )
}
