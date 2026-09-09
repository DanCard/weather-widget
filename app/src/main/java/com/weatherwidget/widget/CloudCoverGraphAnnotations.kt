package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.core.content.ContextCompat
import com.weatherwidget.R
import com.weatherwidget.shared.graph.CloudWatermarkPlacement
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphEmptySpaceFinder
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import java.time.Duration

/**
 * Draws annotations, watermark icon, and dominant station labels on cloud cover graph.
 * Extracted from [CloudCoverGraphRenderer].
 */
internal object CloudCoverGraphAnnotations {

    private const val TAG = "CloudCoverGraphAnnotations"

    val WATERMARK_VERT_FRACTIONS = listOf(0.5f, 0.65f, 0.35f)
    const val WATERMARK_ICON_CURVE_GAP_DP = 2f

    private fun scaledPaint(base: Paint, scale: Float): Paint =
        if (scale == 1f) base else Paint(base).apply { textSize = base.textSize * scale }

    fun drawWatermark(
        context: Context,
        canvas: Canvas,
        smoothedValues: List<Float>,
        points: List<Pair<Float, Float>>,
        graphTop: Float,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
        onWatermarkPlaced: ((CloudCoverGraphRenderer.WatermarkPlacementDebug) -> Unit)?,
    ) {
        val cloudDrawable = ContextCompat.getDrawable(context, R.drawable.ic_weather_mostly_cloudy)
        if (cloudDrawable != null && points.size >= 3) {
            val iconSizePx = dpToPx(HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            val iconGap = dpToPx(WATERMARK_ICON_CURVE_GAP_DP)
            val candidateCenters = CloudWatermarkPlacement.candidateCenters(smoothedValues)

            var placed = false
            var placedCandidateIndex: Int? = null

            for (candidateCenter in candidateCenters) {
                val curveX = points[candidateCenter].first
                val curveY = points[candidateCenter].second

                for (fraction in WATERMARK_VERT_FRACTIONS) {
                    val centerY = graphTop + (curveY - graphTop) * fraction
                    val bounds = RectF(
                        curveX - iconSizePx / 2f,
                        centerY - iconSizePx / 2f,
                        curveX + iconSizePx / 2f,
                        centerY + iconSizePx / 2f,
                    )

                    val fitsAboveCurve = bounds.top >= 0f && bounds.bottom < curveY - iconGap
                    val overlapsLabels = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                    val overlapsIcons = drawnIconBounds.any { RectF.intersects(it, bounds) }
                    if (!fitsAboveCurve || overlapsLabels || overlapsIcons) continue

                    cloudDrawable.alpha = HourlyGraphDefaults.WATERMARK_ALPHA
                    cloudDrawable.setBounds(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    cloudDrawable.draw(canvas)
                    placed = true
                    placedCandidateIndex = candidateCenter
                    break
                }

                if (placed) break
            }

            onWatermarkPlaced?.invoke(
                CloudCoverGraphRenderer.WatermarkPlacementDebug(
                    placed = placed,
                    candidateCenterIndex = placedCandidateIndex,
                ),
            )
        }
    }

    fun drawDominantStationLabel(
        context: Context,
        canvas: Canvas,
        dominantStationLabel: DominantStationLabel.LabelText,
        hours: List<CloudCoverGraphRenderer.CloudHourData>,
        paints: CloudCoverGraphStyle.PaintSet,
        topPadding: Float,
        graphTop: Float,
        graphBottom: Float,
        graphHeight: Float,
        widthPx: Int,
        hourWidth: Float,
        smoothedValues: List<Float>,
        actualPoints: List<Pair<Float, Float>>,
        drawnLabelBounds: MutableList<RectF>,
        layerGlyphBounds: List<GraphRect>,
        nowX: Float?,
        verticalScale: CloudCoverGraphRenderer.VerticalScaleDebug,
        labelScale: Float,
        dpToPx: (Float) -> Float,
        onDominantStationPlaced: ((DominantStationLabel.Placement?) -> Unit)?,
    ) {
        if (hours.size < 2) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "ActualsSourceDiag: reason=too_few_hours text=${dominantStationLabel.fullText}")
            }
            return
        }
        val spanHours = Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
        if (spanHours > DominantStationLabel.MAX_HOURS_SPAN) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(
                    TAG,
                    "ActualsSourceDiag: reason=span_too_wide spanH=$spanHours " +
                        "maxSpanH=${DominantStationLabel.MAX_HOURS_SPAN} text=${dominantStationLabel.fullText}",
                )
            }
            return
        }

        val valuePaint = paints.dominantValueTextPaint
        val stationPaint = paints.dominantStationTextPaint
        val timePaint = paints.dominantTimeTextPaint
        fun paintFor(part: DominantStationLabel.Part, scale: Float): Paint =
            when (part) {
                DominantStationLabel.Part.TEMPERATURE -> scaledPaint(valuePaint, scale)
                DominantStationLabel.Part.TIME -> scaledPaint(timePaint, scale)
                DominantStationLabel.Part.STATION,
                DominantStationLabel.Part.AT,
                DominantStationLabel.Part.AMPM,
                DominantStationLabel.Part.SOURCE_PREFIX -> scaledPaint(stationPaint, scale)
            }
        val padPx = dpToPx(2f * labelScale)
        val scaled =
            DominantStationLabel.placeWithFontFallback(
            text = dominantStationLabel.fullText,
            spanHours = spanHours,
            plot = GraphRect(0f, topPadding, widthPx.toFloat(), graphBottom),
            drawnBounds = drawnLabelBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) } +
                layerGlyphBounds,
            curveYsAt = { x ->
                buildList {
                    if (smoothedValues.size >= 2 && hourWidth > 0f) {
                        val fraction = (x / hourWidth).coerceIn(0f, smoothedValues.lastIndex.toFloat())
                        val idx = fraction.toInt().coerceIn(0, smoothedValues.size - 2)
                        val f = fraction - idx
                        val forecastY = CloudCoverGraphRenderer.mapCloudCoverToY(
                            cloudCover = smoothedValues[idx] + (smoothedValues[idx + 1] - smoothedValues[idx]) * f,
                            graphBottom = graphBottom,
                            graphHeight = graphHeight,
                            topScale = verticalScale.topScale,
                        )
                        add(forecastY)
                    }
                    if (actualPoints.isNotEmpty() && x <= actualPoints.last().first + hourWidth * 0.5f) {
                        val actualIdx = actualPoints.indexOfLast { it.first <= x }
                        if (actualIdx >= 0 && actualIdx < actualPoints.lastIndex) {
                            val p1 = actualPoints[actualIdx]
                            val p2 = actualPoints[actualIdx + 1]
                            val span = p2.first - p1.first
                            if (span > 0f) {
                                val actualY = p1.second + (p2.second - p1.second) * ((x - p1.first) / span).coerceIn(0f, 1f)
                                add(actualY)
                            }
                        } else if (actualIdx == actualPoints.lastIndex) {
                            add(actualPoints.last().second)
                        }
                    }
                }
            },
            metricsForScale = { scale ->
                val widths =
                    dominantStationLabel.segments.map { segment ->
                        paintFor(segment.part, scale).measureText(segment.text)
                    }
                val valuePaintScaled = paintFor(DominantStationLabel.Part.TEMPERATURE, scale)
                GraphEmptySpaceFinder.Metrics(
                    width = widths.sum(),
                    ascent = TemperatureGraphStyle.fontAscent(valuePaintScaled),
                    descent = TemperatureGraphStyle.fontDescent(valuePaintScaled),
                )
            },
            padPx = padPx,
            vetoBounds = if (nowX != null) listOf(GraphRect(nowX - 4f, graphTop, nowX + 4f, graphBottom)) else emptyList(),
        )
        if (scaled != null) {
            val placement = scaled.placement
            var x = placement.box.left
            dominantStationLabel.segments.forEach { segment ->
                val paint = paintFor(segment.part, scaled.fontScale)
                canvas.drawText(segment.text, x, placement.baselineY, paint)
                x += paint.measureText(segment.text)
            }
            drawnLabelBounds.add(RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom))
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(
                    TAG,
                    "ActualsSourceDiag: reason=drawn spanH=$spanHours fontScale=${scaled.fontScale} " +
                        "text=${dominantStationLabel.fullText}",
                )
            }
            onDominantStationPlaced?.invoke(placement)
        } else {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(
                    TAG,
                    "ActualsSourceDiag: reason=no_empty_band spanH=$spanHours fontScale=1.0 " +
                        "text=${dominantStationLabel.fullText}",
                )
            }
            onDominantStationPlaced?.invoke(null)
        }
    }
}
