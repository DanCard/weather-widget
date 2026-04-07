package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudCoverGraphRenderer {

    private const val TAG = "CloudCoverGraph"
    private const val MIN_ICON_GRAPH_WIDTH_PX = 420
    // Not sure yet whether 5 or 6 labels is the better cap here; start with 5 for now.
    private const val MAX_CLOUD_PERCENT_LABEL_CANDIDATES = 5
    private val DENSE_LABEL_DIFF_THRESHOLDS = listOf(8, 12, 16)
    private const val NEARBY_LABEL_WINDOW = 3
    private const val LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT = 20
    private const val LOW_CLOUD_BELOW_OVERFLOW_DP = 10f

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

    data class DayLabelPlacementDebug(
        val side: String,       // "LEFT" or "RIGHT"
        val dayText: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
        val isToday: Boolean,
    )

    data class WatermarkPlacementDebug(
        val placed: Boolean,
        val candidateCenterIndex: Int? = null,
    )

    private class PaintSet(
        val density: Float,
        val tallGraph: Boolean,
        val curvePaint: Paint,
        val gradientPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val percentLabelPaint: Paint,
        val nowLabelTextPaint: Paint,
        val dayLabelTextPaint: Paint,
        val todayDayLabelPaint: Paint,
    )

    private var cachedPaints: PaintSet? = null

    private fun ensurePaints(context: Context, tallGraph: Boolean): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.tallGraph == tallGraph) {
            return current
        }

        val curveStrokeDp = if (tallGraph) 1.5f else 2f
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            strokeWidth = dpToPx(context, curveStrokeDp)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
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

        val paints = PaintSet(
            density = density,
            tallGraph = tallGraph,
            curvePaint = curvePaint,
            gradientPaint = gradientPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            percentLabelPaint = percentLabelPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
        )
        cachedPaints = paints
        return paints
    }

    fun renderGraph(
        context: Context,
        hours: List<CloudHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 1,
        hourLabelSpacingDp: Float = 28f,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onWatermarkPlaced: ((WatermarkPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density
        val tallGraph = heightDp >= 160

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

        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)

        // --- Paints (gray color scheme, cached by density + height band) ---
        val paints = ensurePaints(context, tallGraph)
        paints.gradientPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            Color.parseColor("#44AAAAAA"),
            Color.parseColor("#00AAAAAA"),
            Shader.TileMode.CLAMP,
        )

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawValues = hours.map { it.cloudCover.coerceIn(0, 100).toFloat() }
        val smoothedValues = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawValues, iterations = smoothIterations)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val v = smoothedValues[index]
            val y = graphBottom - graphHeight * (v / 100f)
            points.add(x to y)
        }

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(points, graphBottom)
        canvas.drawPath(fillPath, paints.gradientPaint)
        canvas.drawPath(curvePath, paints.curvePaint)

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
            hourLabelTextPaint = paints.hourLabelTextPaint,
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
        val filteredCandidates = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            maxCandidates = MAX_CLOUD_PERCENT_LABEL_CANDIDATES,
            diffThresholds = DENSE_LABEL_DIFF_THRESHOLDS,
            valueFunction = { it },
            logTag = TAG,
        )
        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = filteredCandidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            valueFunction = { it },
        )

        for (index in filteredCandidates) {
            if (index !in labelSignal.indices) continue
            if (index == 0 && suppressLeftEdgeLabel) {
                Log.d(TAG, "labelSkipped: idx=0 reason=nearby_lower_valley")
                continue
            }
            val cloudPct = labelSignal[index]
            val labelText = "$cloudPct%"
            val fontMetrics = paints.percentLabelPaint.fontMetrics
            val textAscent = fontMetrics?.ascent ?: -paints.percentLabelPaint.textSize
            val textDescent = fontMetrics?.descent ?: 0f
            val textWidth = paints.percentLabelPaint.measureText(labelText)
            val centerX = points[index].first
            val y = points[index].second

            val isPeak = index == globalMaxIdx || (index > 0 && index < labelSignal.lastIndex &&
                labelSignal[index] > labelSignal[index - 1] && labelSignal[index] > labelSignal[index + 1])
            val isEndLabelCandidate = index == hours.lastIndex
            val isRisingAtEnd = isEndLabelCandidate &&
                index > 0 &&
                points[index].second < points[index - 1].second - 0.5f
            val isFallingFromLeftEdge =
                index == 0 &&
                    points.size > 1 &&
                    points[1].second > points[0].second + 0.5f
            val preferAbove = isPeak || isRisingAtEnd || isFallingFromLeftEdge

            val attempts = if (preferAbove) {
                listOf(true, false)
            } else {
                listOf(false, true)
            }
            val hourLabel = hours[index].label

            Log.d(
                TAG,
                "labelCandidate: idx=$index hour=$hourLabel value=$cloudPct% isPeak=$isPeak " +
                    "isGlobalMax=${index == globalMaxIdx} isGlobalMin=${index == globalMinIdx} " +
                    "isEndLabelCandidate=$isEndLabelCandidate isRisingAtEnd=$isRisingAtEnd " +
                    "preferAbove=$preferAbove order=${attempts.joinToString("->") { if (it) "above" else "below" }}",
            )

            for ((attemptIndex, placeAbove) in attempts.withIndex()) {
                val isFallbackAttempt = attemptIndex > 0
                val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = isFallbackAttempt)
                val gapPx = if (placeAbove) dpToPx(context, gapDp.aboveDp) else dpToPx(context, gapDp.belowDp)
                val x = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val verticalPlacement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                    pointY = y,
                    placeAbove = placeAbove,
                    gapPx = gapPx,
                    textAscent = textAscent,
                    textDescent = textDescent,
                )
                val baselineY = verticalPlacement.baselineY
                val bounds = RectF(
                    x - textWidth / 2f, verticalPlacement.top,
                    x + textWidth / 2f, verticalPlacement.bottom,
                )

                val safeBottom = graphBottom - dpToPx(context, 2f)
                val lowCloudBelowOverflowPx = dpToPx(context, LOW_CLOUD_BELOW_OVERFLOW_DP)
                val allowBottomOverflow =
                    shouldAllowBottomOverflow(
                        cloudPct = cloudPct,
                        placeAbove = placeAbove,
                        isFallbackAttempt = isFallbackAttempt,
                    )
                val exceedsTop = bounds.top < 0f
                val exceedsBottom =
                    if (allowBottomOverflow) {
                        bounds.bottom > safeBottom + lowCloudBelowOverflowPx
                    } else {
                        bounds.bottom > safeBottom
                    }
                if (exceedsTop || exceedsBottom) {
                    Log.d(
                        TAG,
                        "labelRejected: idx=$index hour=$hourLabel value=$cloudPct% side=${if (placeAbove) "above" else "below"} " +
                            "attempt=${if (isFallbackAttempt) "fallback" else "preferred"} reason=out_of_bounds " +
                            "exceedsTop=$exceedsTop exceedsBottom=$exceedsBottom " +
                            "allowBottomOverflow=$allowBottomOverflow safeBottom=$safeBottom " +
                            "overflowAllowancePx=${if (allowBottomOverflow) lowCloudBelowOverflowPx else 0f} bounds=$bounds",
                    )
                    continue
                }
                val overlapsLabel = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                val allowIconOverlap =
                    shouldAllowIconOverlap(
                        cloudPct = cloudPct,
                        placeAbove = placeAbove,
                        isFallbackAttempt = isFallbackAttempt,
                    )
                if (overlapsLabel || (overlapsIcon && !allowIconOverlap)) {
                    val reason = if (overlapsLabel) "overlap_label" else "overlap_icon"
                    Log.d(
                        TAG,
                        "labelRejected: idx=$index hour=$hourLabel value=$cloudPct% side=${if (placeAbove) "above" else "below"} " +
                            "attempt=${if (isFallbackAttempt) "fallback" else "preferred"} reason=$reason " +
                            "overlapsLabel=$overlapsLabel overlapsIcon=$overlapsIcon allowIconOverlap=$allowIconOverlap bounds=$bounds",
                    )
                    continue
                }

                canvas.drawText(labelText, x, baselineY, paints.percentLabelPaint)
                drawnLabelBounds.add(bounds)
                Log.d(
                    TAG,
                    "labelPlaced: idx=$index hour=$hourLabel value=$cloudPct% side=${if (placeAbove) "above" else "below"} " +
                        "attempt=${if (isFallbackAttempt) "fallback" else "preferred"} x=$x y=$baselineY",
                )
                onLabelPlaced?.invoke(LabelPlacementDebug(
                    index = index,
                    cloudCover = cloudPct,
                    placedAbove = placeAbove,
                    isGlobalMax = index == globalMaxIdx,
                    isGlobalMin = index == globalMinIdx,
                ))
                break
            }
        }

        // --- Day labels ---
        val today = currentTime.toLocalDate()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText = hours.first().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        val leftPaint = if (leftDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val rightPaint = if (rightDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val leftTextWidth = leftPaint.measureText(leftText)
        val rightTextWidth = rightPaint.measureText(rightText)

        GraphRenderUtils.drawDayLabels(
            context = context,
            canvas = canvas,
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftTextWidth / 2f,
            rightX = widthPx - rightTextWidth / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelTextPaint = paints.dayLabelTextPaint,
            todayDayLabelPaint = paints.todayDayLabelPaint,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = { dpToPx(context, it) },
            onDayLabelPlaced = if (onDayLabelPlaced != null) { side, text, date, x, y, placement, isToday ->
                onDayLabelPlaced.invoke(DayLabelPlacementDebug(side, text, date, x, y, placement, isToday))
            } else null,
        )

        // --- NOW indicator ---
        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = paints.currentTimePaint,
            nowLabelTextPaint = paints.nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        // --- Cloud icon in emptiest region ---
        val cloudDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_mostly_cloudy)
        if (cloudDrawable != null && points.size >= 3) {
            val iconSizePx = dpToPx(context, 20f).toInt()
            val windowSize = (points.size / 5).coerceIn(3, 6)
            val iconGap = dpToPx(context, 2f)
            val candidateCenters =
                (0..points.size - windowSize)
                    .map { start ->
                        val avg = (start until start + windowSize).map { smoothedValues[it] }.average().toFloat()
                        val center = start + windowSize / 2
                        val edgeDistance = minOf(center, points.lastIndex - center)
                        Triple(center, avg, edgeDistance)
                    }
                    .sortedWith(compareBy<Triple<Int, Float, Int>> { it.second }.thenByDescending { it.third })
                    .map { it.first }
                    .distinct()

            var placed = false
            var placedCandidateIndex: Int? = null

            for (candidateCenter in candidateCenters) {
                val curveX = points[candidateCenter].first
                val curveY = points[candidateCenter].second
                val verticalFractions = listOf(0.5f, 0.65f, 0.35f)

                for (fraction in verticalFractions) {
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

                    cloudDrawable.alpha = 96
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
                WatermarkPlacementDebug(
                    placed = placed,
                    candidateCenterIndex = placedCandidateIndex,
                ),
            )
        }

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)

    @androidx.annotation.VisibleForTesting
    internal fun shouldAllowBottomOverflow(
        cloudPct: Int,
        placeAbove: Boolean,
        isFallbackAttempt: Boolean,
    ): Boolean =
        !placeAbove &&
            !isFallbackAttempt &&
            cloudPct <= LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT

    @androidx.annotation.VisibleForTesting
    internal fun shouldAllowIconOverlap(
        cloudPct: Int,
        placeAbove: Boolean,
        isFallbackAttempt: Boolean,
    ): Boolean =
        !placeAbove &&
            !isFallbackAttempt &&
            cloudPct <= LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT
}
