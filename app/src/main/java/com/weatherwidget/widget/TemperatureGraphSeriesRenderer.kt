package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathMeasure
import com.weatherwidget.shared.graph.GhostLineGate
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.util.WeatherConditionColors
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

internal object TemperatureGraphSeriesRenderer {
    private const val MIN_GHOST_LINE_DELTA = 0.1f
    private const val TRANSITION_CLIP_EXTRA_DP = 1f

    data class Input(
        val context: Context,
        val canvas: Canvas,
        val widthPx: Int,
        val heightPx: Int,
        val labelScale: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val minTemp: Float,
        val maxTemp: Float,
        val tempRange: Float,
        val currentTime: LocalDateTime,
        val appliedDelta: Float?,
        val lastObservedTemp: Float?,
        val hours: List<HourData>,
        val series: TemperatureGraphSeriesGeometry,
        val paints: PaintSet,
        val onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
    )

    data class GhostGateInput(
        val hours: List<HourData>,
        val currentTime: LocalDateTime,
        val fetchDotX: Float?,
        val widthPx: Int,
        val nowIndicatorVisible: Boolean,
        val appliedDelta: Float?,
    )

    fun draw(input: Input) {
        val processGhost =
            shouldRenderGhostLine(
                GhostGateInput(
                    hours = input.hours,
                    currentTime = input.currentTime,
                    fetchDotX = input.series.fetchDotX,
                    widthPx = input.widthPx,
                    nowIndicatorVisible = input.series.nowIndicatorVisible,
                    appliedDelta = input.appliedDelta,
                ),
            )
        if (processGhost) {
            val expectedFillPaint =
                Paint(input.paints.expectedFillPaint).apply {
                    shader =
                        TemperatureGraphStyle.buildTempGradient(
                            input.graphTop,
                            input.graphBottom,
                            input.minTemp,
                            input.maxTemp,
                            input.tempRange,
                            alphaTop = TemperatureGraphStyle.EXPECTED_FILL_ALPHA_TOP,
                            alphaBottom = TemperatureGraphStyle.EXPECTED_FILL_ALPHA_BOTTOM,
                        )
                }
            input.canvas.drawPath(input.series.expectedFillPath, expectedFillPaint)

            val fetchDotX = requireNotNull(input.series.fetchDotX)
            input.lastObservedTemp?.let {
                input.onGhostLineDebug?.invoke(
                    GhostLineDebug(
                        startX = fetchDotX,
                        startY =
                            TemperatureGraphStyle.tempToY(
                                it,
                                input.graphTop,
                                input.graphBottom - input.graphTop,
                                input.minTemp,
                                input.tempRange,
                            ),
                    ),
                )
            }
            input.canvas.save()
            input.canvas.clipRect(
                fetchDotX.coerceAtLeast(0f),
                0f,
                input.widthPx.toFloat(),
                input.heightPx.toFloat(),
            )
            input.canvas.drawPath(input.series.expectedPath, input.paints.ghostPaint)
            input.canvas.restore()
        }

        drawForecastSegments(input)
        drawActualSeries(input)
    }

    fun shouldRenderGhostLine(input: GhostGateInput): Boolean {
        if (input.hours.size < 2) return false
        val spanHours =
            Duration.between(input.hours.first().dateTime, input.hours.last().dateTime).toHours()
        val hoursFromNowToWindowStart =
            Duration.between(input.currentTime, input.hours.first().dateTime).toHours()
        return GhostLineGate.shouldProcess(
            fetchDotX = input.fetchDotX,
            graphWidthPx = input.widthPx.toFloat(),
            spanHours = spanHours,
            nowIndicatorVisible = input.nowIndicatorVisible,
            hoursFromNowToWindowStart = hoursFromNowToWindowStart,
        ) &&
            input.appliedDelta != null &&
            abs(input.appliedDelta) >= MIN_GHOST_LINE_DELTA &&
            input.fetchDotX != null
    }

    @androidx.annotation.VisibleForTesting
    internal fun resolveForecastSegmentColors(
        hours: List<HourData>,
        segments: List<AndroidCurvePathBuilder.IndexedCurvePath>,
    ): List<Int> =
        segments.map { segment ->
            val hour =
                hours.getOrNull(segment.endPointIndex)
                    ?: error(
                        "Curve segment end index ${segment.endPointIndex} is outside " +
                            "${hours.size} hourly items",
                    )
            WeatherConditionColors.forecastColor(
                hour.isSunny,
                hour.isRainy,
                hour.isMixed,
                hour.isNight,
                hour.isTwilight,
            )
        }

    private fun drawForecastSegments(input: Input) {
        val dashOn =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.FORECAST_DASH_ON_DP,
            )
        val dashOff =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.FORECAST_DASH_OFF_DP,
            )
        val dashPattern = floatArrayOf(dashOn, dashOff)
        val segmentPaint = Paint(input.paints.forecastDashedPaint)
        val pathMeasure = PathMeasure()
        var cumulativeLength = 0f
        val segmentColors =
            resolveForecastSegmentColors(
                input.hours,
                input.series.forecastSegmentPaths,
            )
        input.series.forecastSegmentPaths.forEachIndexed { index, segment ->
            if (segment.startsContour) cumulativeLength = 0f
            segmentPaint.color = segmentColors[index]
            segmentPaint.pathEffect = DashPathEffect(dashPattern, cumulativeLength)
            input.canvas.drawPath(segment.path, segmentPaint)
            pathMeasure.setPath(segment.path, false)
            cumulativeLength += pathMeasure.length
        }
    }

    private fun drawActualSeries(input: Input) {
        val transitionX = input.series.transitionX ?: return
        val transitionClipExtra =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TRANSITION_CLIP_EXTRA_DP,
            )
        if (transitionX + transitionClipExtra <= 0f) return

        input.canvas.save()
        input.canvas.clipRect(
            0f,
            0f,
            transitionX + transitionClipExtra,
            input.heightPx.toFloat(),
        )
        input.canvas.drawPath(input.series.actualPath, input.paints.actualLinePaint)
        input.canvas.restore()
    }
}
