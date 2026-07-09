package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant

import com.weatherwidget.BuildConfig

import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.TemperatureRole
import com.weatherwidget.shared.graph.LabelPlacementDebug
import com.weatherwidget.shared.graph.PlacedLabelMeta

data class PaintSet(
    val density: Float,
    val labelScale: Float,
    val actualLinePaint: android.graphics.Paint,
    val forecastDashedPaint: android.graphics.Paint,
    val ghostPaint: android.graphics.Paint,
    val expectedFillPaint: android.graphics.Paint,
    val currentTimePaint: android.graphics.Paint,
    val hourLabelTextPaint: android.graphics.Paint,
    val actualTempLabelTextPaint: android.graphics.Paint,
    val forecastTempLabelTextPaint: android.graphics.Paint,
    val nowLabelTextPaint: android.graphics.Paint,
    val dayLabelTextPaint: android.graphics.Paint,
    val todayDayLabelPaint: android.graphics.Paint,
    val ringPaint: android.graphics.Paint,
    val outerRingPaint: android.graphics.Paint,
    val valueTextPaint: android.graphics.Paint,
    val stalenessTextPaint: android.graphics.Paint,
    val actualLeaderLinePaint: android.graphics.Paint,
    val forecastLeaderLinePaint: android.graphics.Paint,
    val dotPaint: android.graphics.Paint,
)

data class FetchDotDebug(
    val observedAt: Long,
    val fetchDotX: Float?,
    val fetchY: Float? = null,
    val withinWindow: Boolean,
    val ageText: String? = null,
    val valueColor: Int? = null,
    val stalenessColor: Int? = null,
    val stalenessLabelY: Float? = null,
)

data class GhostLineDebug(
    val startX: Float,
    val startY: Float,
)

data class ActualLineDebug(
    val endX: Float?,
    val endY: Float?,
    val pointCount: Int,
    val anchoredToFetchDot: Boolean,
)

data class DayLabelPlacementDebug(
    val side: String,       // "LEFT" or "RIGHT"
    val dayText: String,
    val date: LocalDate,
    val x: Float,
    val y: Float,
    val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
    val isToday: Boolean,
)

data class PointsDebug(
    val original: List<Pair<Float, Float>>,
    val forecast: List<Pair<Float, Float>>,
    val expected: List<Pair<Float, Float>>,
)

data class ValueLabelLayout(
    val x: Float,
    val y: Float,
    val bounds: RectF,
    val align: android.graphics.Paint.Align,
)

data class StalenessInitialLayout(
    val baselineY: Float,
    val bounds: RectF,
    val placeAbove: Boolean,
)

data class Geometry(
    val graphTop: Float,
    val graphBottom: Float,
    val graphHeight: Float,
    val footerTop: Float,
    val hourWidth: Float,
    val minTimeEpoch: Long,
    val iconSize: Int,
    val iconTopPad: Float,
    val minTemp: Float,
    val maxTemp: Float,
    val tempRange: Float,
)

data class GraphData(
    val transitionX: Float?,
    val nowX: Float?,
    val nowIndicatorVisible: Boolean,
    val fetchTime: LocalDateTime?,
    val fetchDotX: Float?,
    val lastObservedTemp: Float?,
    val anchorDelta: Float,
    val smoothedForecastTemps: List<Float>,
    val smoothedExpectedTemps: List<Float>,
    val originalPoints: List<Pair<Float, Float>>,
    val forecastPoints: List<Pair<Float, Float>>,
    val expectedPoints: List<Pair<Float, Float>>,
    val originalPath: Path,
    val actualPath: Path,
    val actualVisiblePoints: List<Pair<Float, Float>>,
    val expectedPath: Path,
    val forecastPath: Path,
    val forecastFillPath: Path,
    val forecastSegmentPaths: List<Path>,
    val effectiveActualEndIndex: Int,
    val appliedDelta: Float?,
    val observedAt: Long?,
)

data class DebugCallbacks(
    val onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
    val onActualLineResolved: ((ActualLineDebug) -> Unit)?,
    val onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
    val onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
    val onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
)

data class RenderContext(
    val context: Context,
    val canvas: Canvas,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val labelScale: Float,
    val geo: Geometry,
    val data: GraphData,
    val paints: PaintSet,
    val currentTime: LocalDateTime,
    val debug: DebugCallbacks,
    val drawnLabelBounds: MutableList<RectF> = mutableListOf(),
    val useCelsius: Boolean = false,
) {
    val graphTop get() = geo.graphTop
    val graphBottom get() = geo.graphBottom
    val graphHeight get() = geo.graphHeight
    val footerTop get() = geo.footerTop
    val minTemp get() = geo.minTemp
    val maxTemp get() = geo.maxTemp
    val tempRange get() = geo.tempRange
    val transitionX get() = data.transitionX
    val nowX get() = data.nowX
    val nowIndicatorVisible get() = data.nowIndicatorVisible
    val fetchTime get() = data.fetchTime
    val fetchDotX get() = data.fetchDotX
    val lastObservedTemp get() = data.lastObservedTemp
    val anchorDelta get() = data.anchorDelta
    val originalPoints get() = data.originalPoints
    val forecastPoints get() = data.forecastPoints
    val expectedPath get() = data.expectedPath
    val actualPath get() = data.actualPath
    val actualVisiblePoints get() = data.actualVisiblePoints
    val forecastPath get() = data.forecastPath
    val forecastFillPath get() = data.forecastFillPath
    val forecastSegmentPaths get() = data.forecastSegmentPaths
    val effectiveActualEndIndex get() = data.effectiveActualEndIndex
    val appliedDelta get() = data.appliedDelta
    val observedAt get() = data.observedAt
    val iconSize get() = geo.iconSize
    val iconTopPad get() = geo.iconTopPad
    val hourWidth get() = geo.hourWidth
    val smoothedForecastTemps get() = data.smoothedForecastTemps
    val smoothedExpectedTemps get() = data.smoothedExpectedTemps
    val expectedPoints get() = data.expectedPoints
    val onGhostLineDebug get() = debug.onGhostLineDebug
    val onActualLineResolved get() = debug.onActualLineResolved
    val onLabelPlaced get() = debug.onLabelPlaced
    val onDayLabelPlaced get() = debug.onDayLabelPlaced
    val onFetchDotResolved get() = debug.onFetchDotResolved

    companion object {
        fun create(
            context: Context,
            canvas: Canvas,
            widthPx: Int,
            heightPx: Int,
            density: Float,
            labelScale: Float,
            minTemp: Float,
            maxTemp: Float,
            tempRange: Float,
            layout: GraphLayout.Layout,
            hourWidth: Float,
            minTimeEpoch: Long,
            update: RenderContextUpdate,
            lastObservedTemp: Float?,
            appliedDelta: Float?,
            observedAt: Long?,
            paints: PaintSet,
            currentTime: LocalDateTime,
            onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
            onActualLineResolved: ((ActualLineDebug) -> Unit)?,
            onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
            onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
            onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
            useCelsius: Boolean = false,
        ): RenderContext = RenderContext(
            context = context,
            canvas = canvas,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            labelScale = labelScale,
            geo = Geometry(
                graphTop = layout.graphTop,
                graphBottom = layout.graphBottom,
                graphHeight = layout.graphHeight,
                footerTop = layout.footerTop,
                hourWidth = hourWidth,
                minTimeEpoch = minTimeEpoch,
                iconSize = layout.iconSize,
                iconTopPad = layout.iconTopPad,
                minTemp = minTemp,
                maxTemp = maxTemp,
                tempRange = tempRange,
            ),
            data = GraphData(
                transitionX = update.transitionX,
                nowX = update.nowX,
                nowIndicatorVisible = update.nowIndicatorVisible,
                fetchTime = update.fetchTime,
                fetchDotX = update.fetchDotX,
                lastObservedTemp = lastObservedTemp,
                anchorDelta = update.anchorDelta,
                smoothedForecastTemps = update.smoothedForecastTemps,
                smoothedExpectedTemps = update.smoothedExpectedTemps,
                originalPoints = update.originalPoints,
                forecastPoints = update.forecastPoints,
                expectedPoints = update.expectedPoints,
                originalPath = update.originalPath,
                actualPath = update.actualPath,
                actualVisiblePoints = update.actualVisiblePoints,
                expectedPath = update.expectedPath,
                forecastPath = update.forecastPath,
                forecastFillPath = update.forecastFillPath,
                forecastSegmentPaths = update.forecastSegmentPaths,
                effectiveActualEndIndex = update.effectiveActualEndIndex,
                appliedDelta = appliedDelta,
                observedAt = observedAt,
            ),
            paints = paints,
            currentTime = currentTime,
            debug = DebugCallbacks(
                onGhostLineDebug = onGhostLineDebug,
                onActualLineResolved = onActualLineResolved,
                onLabelPlaced = onLabelPlaced,
                onDayLabelPlaced = onDayLabelPlaced,
                onFetchDotResolved = onFetchDotResolved,
            ),
            useCelsius = useCelsius,
        )
    }
}

fun RenderContext.tempToY(temp: Float): Float =
    TemperatureGraphStyle.tempToY(temp, graphTop, graphHeight, minTemp, tempRange)

data class RenderContextUpdate(
    val smoothedForecastTemps: List<Float>,
    val smoothedExpectedTemps: List<Float>,
    val originalPoints: List<Pair<Float, Float>>,
    val forecastPoints: List<Pair<Float, Float>>,
    val expectedPoints: List<Pair<Float, Float>>,
    val originalPath: Path,
    val actualPath: Path,
    val actualVisiblePoints: List<Pair<Float, Float>>,
    val expectedPath: Path,
    val expectedFillPath: Path,
    val forecastPath: Path,
    val forecastFillPath: Path,
    val forecastSegmentPaths: List<Path>,
    val nowX: Float?,
    val nowIndicatorVisible: Boolean,
    val fetchTime: LocalDateTime?,
    val fetchDotX: Float?,
    val anchorDelta: Float,
    val transitionX: Float?,
    val effectiveActualEndIndex: Int,
)

class RenderTimings {
    private val marks = mutableListOf<Pair<String, Long>>()
    fun mark(label: String) { marks.add(label to SystemClock.elapsedRealtime()) }
    fun log(widthPx: Int, heightPx: Int, hoursSize: Int, tag: String) {
        if (marks.size < 2) return
        val parts = marks.zipWithNext().map { (a, b) -> "${a.first}=${b.second - a.second}ms" }
        android.util.Log.d(tag, "RENDER_BREAKDOWN size=${widthPx}x${heightPx} hours=$hoursSize ${parts.joinToString(" ")} total=${marks.last().second - marks.first().second}ms")
    }
}
