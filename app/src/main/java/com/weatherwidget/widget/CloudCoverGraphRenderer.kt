package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudCoverGraphRenderer {

    private const val TAG = "CloudCoverGraph"
    private const val MIN_ICON_GRAPH_WIDTH_PX = 420

    data class CloudHourData(
        val dateTime: LocalDateTime,
        val cloudCover: Int, // 0-100
        val label: String,
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true,
    )

    data class LabelPlacementDebug(
        val index: Int,
        val cloudCover: Int,
        val placedAbove: Boolean,
        val isGlobalMax: Boolean,
        val isGlobalMin: Boolean,
    )

    fun renderGraph(
        context: Context,
        hours: List<CloudHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        hourLabelSpacingDp: Float = 28f,
        observedTempFetchedAt: Long? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density

        val topPadding = dpToPx(context, 12f)
        val hasHourlyIcons = hours.any { it.iconRes != null }
        val showHourlyIcons = hasHourlyIcons && widthPx >= MIN_ICON_GRAPH_WIDTH_PX
        val iconSize = dpToPx(context, 16f).toInt()
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)
        val labelHeight = dpToPx(context, 10f)
        val bottomPadding = dpToPx(context, 3f)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - labelHeight - bottomPadding - iconBottomPad - iconSize - iconTopPad
            } else {
                heightPx - labelHeight - bottomPadding
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / hours.size

        // --- Paints (gray color scheme) ---
        val curveStrokeDp = if (heightDp >= 160) 1.5f else 2f
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            strokeWidth = dpToPx(context, curveStrokeDp)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, graphTop, 0f, graphBottom,
                Color.parseColor("#44AAAAAA"),
                Color.parseColor("#00AAAAAA"),
                Shader.TileMode.CLAMP,
            )
        }

        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val percentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 11.0f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")
            textSize = dpToPx(context, 8.5f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
        }

        val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
            color = Color.parseColor("#BBFF9F0A")
        }

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawValues = hours.map { it.cloudCover.coerceIn(0, 100).toFloat() }
        val smoothedValues = GraphRenderUtils.smoothValues(rawValues, iterations = smoothIterations)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index + hourWidth / 2f
            val v = smoothedValues[index]
            val y = graphBottom - graphHeight * (v / 100f)
            points.add(x to y)
        }

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(points, graphBottom)
        canvas.drawPath(fillPath, gradientPaint)
        canvas.drawPath(curvePath, curvePaint)

        // --- Hour labels and icons ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val drawnIconBounds = mutableListOf<RectF>()

        val nowX = GraphRenderUtils.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime },
        )

        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = hours,
            points = points,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
        ) { index, clampedX ->
            if (!showHourlyIcons) return@drawHourLabels
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawHourLabels
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes) ?: return@drawHourLabels

            val iconY = graphBottom + iconTopPad
            val iconX = clampedX - iconSize / 2f
            val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
            drawnIconBounds.add(iconRect)

            drawable.setBounds(
                iconRect.left.toInt(), iconRect.top.toInt(),
                iconRect.right.toInt(), iconRect.bottom.toInt(),
            )

            if (!hour.isRainy && !hour.isMixed) {
                val iconTint = when {
                    hour.isNight -> Color.parseColor("#BBBBBB")
                    hour.isSunny -> Color.parseColor("#FFD60A")
                    else -> Color.parseColor("#BBBBBB")
                }
                drawable.setTint(iconTint)
            }
            drawable.draw(canvas)
        }

        // --- Percentage labels at key points (simplified: extrema + edges) ---
        val labelSignal = smoothedValues.map { it.roundToInt().coerceIn(0, 100) }
        val drawnLabelBounds = mutableListOf<RectF>()
        val aboveGap = dpToPx(context, 4f)
        val belowGap = dpToPx(context, 14f)

        // Find local maxima and minima
        val candidates = mutableListOf<Int>()
        // Global max/min
        val globalMaxIdx = labelSignal.indices.maxByOrNull { labelSignal[it] } ?: -1
        val globalMinIdx = labelSignal.indices.minByOrNull { labelSignal[it] } ?: -1
        if (globalMaxIdx >= 0) candidates.add(globalMaxIdx)
        if (globalMinIdx >= 0 && globalMinIdx != globalMaxIdx) candidates.add(globalMinIdx)
        // Edges
        if (0 !in candidates) candidates.add(0)
        if (hours.lastIndex !in candidates && hours.isNotEmpty()) candidates.add(hours.lastIndex)
        // Local extrema
        for (i in 1 until labelSignal.lastIndex) {
            val prev = labelSignal[i - 1]; val cur = labelSignal[i]; val next = labelSignal[i + 1]
            if ((cur > prev && cur > next) || (cur < prev && cur < next)) {
                if (i !in candidates) candidates.add(i)
            }
        }

        candidates.sortBy { it }

        for (index in candidates) {
            if (index !in labelSignal.indices) continue
            val prob = labelSignal[index]
            val labelText = "$prob%"
            val textWidth = percentLabelPaint.measureText(labelText)
            val textHeight = percentLabelPaint.textSize
            val centerX = points[index].first
            val y = points[index].second

            val isPeak = index == globalMaxIdx || (index > 0 && index < labelSignal.lastIndex &&
                labelSignal[index] > labelSignal[index - 1] && labelSignal[index] > labelSignal[index + 1])
            val preferAbove = isPeak || prob <= 50

            val attempts = if (preferAbove) {
                listOf(true, false)
            } else {
                listOf(false, true)
            }

            for (placeAbove in attempts) {
                val x = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val baselineY = if (placeAbove) y - aboveGap else y + belowGap
                val bounds = RectF(
                    x - textWidth / 2f, baselineY - textHeight,
                    x + textWidth / 2f, baselineY,
                )

                if (bounds.top < 0f || bounds.bottom > graphBottom - dpToPx(context, 2f)) continue
                val overlaps = drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
                    drawnIconBounds.any { RectF.intersects(it, bounds) }
                if (overlaps) continue

                canvas.drawText(labelText, x, baselineY, percentLabelPaint)
                drawnLabelBounds.add(bounds)
                onLabelPlaced?.invoke(LabelPlacementDebug(
                    index = index,
                    cloudCover = prob,
                    placedAbove = placeAbove,
                    isGlobalMax = index == globalMaxIdx,
                    isGlobalMin = index == globalMinIdx,
                ))
                break
            }
        }

        // --- Day labels ---
        val fm = dayLabelTextPaint.fontMetrics ?: Paint.FontMetrics()
        val dayLabelTextHeight = fm.descent - fm.ascent
        val dayYTop = graphTop + dayLabelTextHeight
        val dayYMid = (graphTop + graphBottom) / 2f

        val today = java.time.LocalDate.now()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText = hours.first().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())

        data class DayCandidate(val date: java.time.LocalDate, val x: Float, val text: String)
        val leftPaint = if (leftDate == today) todayDayLabelPaint else dayLabelTextPaint
        val rightPaint = if (rightDate == today) todayDayLabelPaint else dayLabelTextPaint
        val leftTextWidth = leftPaint.measureText(leftText)
        val rightTextWidth = rightPaint.measureText(rightText)

        val dayCandidates = listOf(
            DayCandidate(leftDate, leftTextWidth / 2f, leftText),
            DayCandidate(rightDate, widthPx - rightTextWidth / 2f, rightText),
        )

        fun dayBounds(x: Float, y: Float, w: Float) =
            RectF(x - w / 2f, y + fm.ascent, x + w / 2f, y + fm.descent)

        fun collides(bounds: RectF) =
            drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
                drawnIconBounds.any { RectF.intersects(it, bounds) }

        for (candidate in dayCandidates) {
            val paint = if (candidate.date == today) todayDayLabelPaint else dayLabelTextPaint
            val tw = paint.measureText(candidate.text)
            val topBounds = dayBounds(candidate.x, dayYTop, tw)
            if (!collides(topBounds)) {
                canvas.drawText(candidate.text, candidate.x, dayYTop, paint)
            } else {
                val midBounds = dayBounds(candidate.x, dayYMid, tw)
                canvas.drawText(candidate.text, candidate.x, if (!collides(midBounds)) dayYMid else heightPx - dpToPx(context, 14f), paint)
            }
        }

        // --- NOW indicator ---
        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = currentTimePaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        // --- Cloud icon in emptiest region ---
        val cloudDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_mostly_cloudy)
        if (cloudDrawable != null && points.size >= 3) {
            val iconSizePx = dpToPx(context, 20f).toInt()
            val windowSize = (points.size / 5).coerceIn(3, 6)
            val iconGap = dpToPx(context, 2f)

            var lowStart = 0; var lowAvg = Float.MAX_VALUE
            for (start in 0..points.size - windowSize) {
                val avg = (start until start + windowSize).map { smoothedValues[it] }.average().toFloat()
                if (avg < lowAvg) { lowAvg = avg; lowStart = start }
            }
            val lowCenter = lowStart + windowSize / 2
            val lowX = points[lowCenter].first
            val lowCurveY = points[lowCenter].second
            val aboveCenterY = graphTop + (lowCurveY - graphTop) / 2f
            val aboveBounds = RectF(
                lowX - iconSizePx / 2f, aboveCenterY - iconSizePx / 2f,
                lowX + iconSizePx / 2f, aboveCenterY + iconSizePx / 2f,
            )
            if (aboveBounds.top >= 0f && aboveBounds.bottom < lowCurveY - iconGap &&
                !drawnLabelBounds.any { RectF.intersects(it, aboveBounds) }
            ) {
                cloudDrawable.alpha = 80
                cloudDrawable.setTint(Color.parseColor("#BBBBBB"))
                cloudDrawable.setBounds(
                    aboveBounds.left.toInt(), aboveBounds.top.toInt(),
                    aboveBounds.right.toInt(), aboveBounds.bottom.toInt(),
                )
                cloudDrawable.draw(canvas)
            }
        }

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
