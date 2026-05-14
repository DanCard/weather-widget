package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudCoverGraphRenderer {

    private const val TAG = "CloudCoverGraph"
    private const val MAX_CLOUD_PERCENT_LABEL_CANDIDATES = HourlyGraphDefaults.MAX_LABEL_CANDIDATES
    private val DENSE_LABEL_DIFF_THRESHOLDS = HourlyGraphDefaults.DENSE_LABEL_DIFF_THRESHOLDS
    private const val LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT = 55
    private const val LOW_CLOUD_BELOW_OVERFLOW_DP = 10f

    private const val GRAPH_TOP_PADDING_DP = 38f
    private const val GRAPH_BOTTOM_PADDING_DP = 3f
    private const val TOP_SCALE_HEADROOM_PERCENT = 12f
    private const val MIN_DYNAMIC_TOP_SCALE_PERCENT = 85f
    private const val MAX_DYNAMIC_TOP_SCALE_PERCENT = 100f
    private const val SOFT_DIP_MAX_PERCENT = 85
    private const val SOFT_DIP_MIN_DIFF = 15
    private const val WATERMARK_WINDOW_DIVISOR = 5
    private const val WATERMARK_WINDOW_MIN = 3
    private const val WATERMARK_WINDOW_MAX = 6
    private val WATERMARK_VERT_FRACTIONS = listOf(0.5f, 0.65f, 0.35f)
    private const val WATERMARK_ICON_CURVE_GAP_DP = 2f

    private const val COLOR_CLOUD_GRADIENT_START = "#44AAAAAA"
    private const val COLOR_CLOUD_GRADIENT_END = "#00AAAAAA"
    private const val COLOR_MISSING_DIAG_TEXT = "#DDC8CFD8"
    private const val COLOR_MISSING_DIAG_SHADOW = "#CC000000"
    private const val COLOR_MISSING_DIAG_REASON_TEXT = "#AAB0B6BE"

    private const val MISSING_DIAG_TEXT_SIZE_DP = 9f
    private const val MISSING_DIAG_REASON_TEXT_SIZE_DP = 7.5f
    private const val MISSING_DIAG_MIN_LABEL_SCALE = 0.85f
    private const val MISSING_DIAG_LINE_SPACING = 1.15f
    private const val MISSING_DIAG_SHADOW_RADIUS_DP = 3f
    private const val MISSING_DIAG_SHADOW_DY_DP = 1f

    data class CloudHourData(
        val dateTime: LocalDateTime,
        val cloudCover: Int, // 0-100
        val label: String,
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isTwilight: Boolean = false,
        val isSunBoundary: Boolean = false,
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

    @androidx.annotation.VisibleForTesting
    internal data class VerticalScaleDebug(
        val visibleMax: Float,
        val topScale: Float,
    )

    private fun ensurePaints(context: Context, tallGraph: Boolean, labelScale: Float) =
        CloudCoverGraphStyle.ensurePaints(context, tallGraph, labelScale)

    fun renderGraph(
        context: Context,
        hours: List<CloudHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 1,
        hourLabelSpacingDp: Float = HourlyGraphDefaults.DEFAULT_HOUR_LABEL_SPACING_DP,
        // Total number of hours in the visible window and how many lack cloud cover data.
        // Used to render an in-graph "data missing" diagnostic when the upstream feed has
        // gaps, so the user sees the gap honestly instead of guessing whether the sky was
        // clear or the fetch failed. When totalHours is 0 these are ignored.
        missingHours: Int = 0,
        totalHours: Int = 0,
        // Number of grid columns available in the widget. Used to inject a middle
        // label on wide widgets when only edges are labeled.
        numColumns: Int = 0,
        // Compact human description of which hours are missing, e.g., "7a–8p" or
        // "9a, 11p". Optional: when null, the diagnostic falls back to the count.
        missingDescription: String? = null,
        // Short upstream reason (e.g., "NWS gridpoints fetch failed"). Renders as a
        // dim second line below the main diagnostic when present.
        missingReason: String? = null,
        job: Job? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onWatermarkPlaced: ((WatermarkPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list (${widthPx}x${heightPx})")
            if (totalHours > 0) {
                drawMissingDataDiagnostic(
                    context, canvas, widthPx, heightPx,
                    missingHours = totalHours, totalHours = totalHours,
                    missingDescription = missingDescription, missingReason = missingReason,
                    labelScale = 1f,
                )
            }
            return bitmap
        }

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density
        val tallGraph = heightDp >= HourlyGraphDefaults.TALL_GRAPH_HEIGHT_DP
        val labelScale = bitmapScale.coerceAtMost(1f)

        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP * labelScale)
        val hasHourlyIcons = hours.any { it.iconRes != null }
        val showHourlyIcons = hasHourlyIcons && widthPx >= HourlyGraphDefaults.MIN_ICON_GRAPH_WIDTH_PX
        val iconSize = dpToPx(context, HourlyGraphDefaults.WEATHER_ICON_SIZE_DP).toInt()
        val iconTopPad = dpToPx(context, 0f)
        val iconBottomPad = dpToPx(context, 0f)
        val labelHeight = dpToPx(context, HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)
        val bottomPadding = dpToPx(context, GRAPH_BOTTOM_PADDING_DP * labelScale)

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
        val paints = ensurePaints(context, tallGraph, labelScale)
        paints.gradientPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            Color.parseColor(COLOR_CLOUD_GRADIENT_START),
            Color.parseColor(COLOR_CLOUD_GRADIENT_END),
            Shader.TileMode.CLAMP,
        )

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawValues = hours.map { it.cloudCover.coerceIn(0, 100).toFloat() }
        val smoothedValues = GraphRenderUtils.smoothValuesPreservingAllExtrema(rawValues, iterations = smoothIterations)
        val verticalScale = computeVerticalScale(smoothedValues)
        Log.d(
            TAG,
            "verticalScale: visibleMax=${verticalScale.visibleMax} topScale=${verticalScale.topScale} " +
                "graphTop=$graphTop graphBottom=$graphBottom graphHeight=$graphHeight",
        )

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val v = smoothedValues[index]
            val y = mapCloudCoverToY(
                cloudCover = v,
                graphBottom = graphBottom,
                graphHeight = graphHeight,
                topScale = verticalScale.topScale,
            )
            points.add(x to y)
        }
        val peakIndex = smoothedValues.indices.maxByOrNull { smoothedValues[it] } ?: -1
        if (peakIndex >= 0) {
            Log.d(
                TAG,
                "peakPoint: idx=$peakIndex value=${smoothedValues[peakIndex]} " +
                    "x=${points[peakIndex].first} y=${points[peakIndex].second} topPaddingPx=$topPadding",
            )
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
                    hour.isNight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_NIGHT)
                    hour.isTwilight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_TWILIGHT)
                    hour.isSunny -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_SUNNY)
                    else -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_DEFAULT)
                }
                drawable.setTint(iconTint)
            }
            drawable.draw(canvas)
        }

        // --- Percentage labels at key points (simplified: extrema + edges) ---
        val labelSignal = smoothedValues.map { it.roundToInt().coerceIn(0, 100) }
        val drawnLabelBounds = mutableListOf<RectF>()
        // Find local maxima and minima
        val localMaxima = GraphRenderUtils.findLocalExtremaIndices(labelSignal, isMax = true)
        val localMinima = GraphRenderUtils.findLocalExtremaIndices(labelSignal, isMax = false)

        val globalMaxVal = labelSignal.maxOrNull() ?: -1
        val globalMinVal = labelSignal.minOrNull() ?: -1
        
        // Pick the plateau center if it exists, otherwise first occurrence.
        val globalMaxIdx = localMaxima.firstOrNull { labelSignal[it] == globalMaxVal }
            ?: labelSignal.indexOfFirst { it == globalMaxVal }
        val globalMinIdx = localMinima.firstOrNull { labelSignal[it] == globalMinVal }
            ?: labelSignal.indexOfFirst { it == globalMinVal }

        // Soft dip candidates (mandatory)
        val softDipCandidates = mutableListOf<Int>()
        var jIdx = 0
        while (jIdx < labelSignal.size) {
            val prob = labelSignal[jIdx]
            if (prob <= 0 || prob > SOFT_DIP_MAX_PERCENT) { // Dips are only relevant if not already fully overcast/clear
                jIdx++
                continue
            }

            // Start of a potential plateau
            var runEnd = jIdx
            while (runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] == prob) {
                runEnd++
            }

            val left = (jIdx - HourlyGraphDefaults.SOFT_DIP_WINDOW_SIZE).coerceAtLeast(0)
            val right = (runEnd + HourlyGraphDefaults.SOFT_DIP_WINDOW_SIZE).coerceAtMost(labelSignal.lastIndex)

            if (left < jIdx && right > runEnd) {
                val leftMax = (left until jIdx).maxOfOrNull { labelSignal[it] } ?: prob
                val rightMax = ((runEnd + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob

                if (leftMax >= prob + SOFT_DIP_MIN_DIFF && rightMax >= prob + SOFT_DIP_MIN_DIFF) {
                    // This plateau is a "soft dip". Add the center.
                    softDipCandidates.add(jIdx + (runEnd - jIdx) / 2)
                }
            }
            jIdx = runEnd + 1
        }

        val candidates = mutableListOf<Int>()
        if (globalMaxIdx >= 0) candidates.add(globalMaxIdx)
        if (globalMinIdx >= 0 && globalMinIdx != globalMaxIdx) candidates.add(globalMinIdx)
        // Edges
        if (0 !in candidates) candidates.add(0)
        if (hours.lastIndex !in candidates && hours.isNotEmpty()) candidates.add(hours.lastIndex)
        // Local extrema
        candidates.addAll(localMaxima)
        candidates.addAll(localMinima)
        // Soft dips
        candidates.addAll(softDipCandidates)

        val protectedIndices = buildSet {
            addAll(softDipCandidates)
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
            protectedIndices = protectedIndices,
            nearbyWindow = HourlyGraphDefaults.LABEL_FILTER_NEARBY_WINDOW,
        )
        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = filteredCandidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            valueFunction = { it },
        )

        val finalCandidates =
            if (numColumns >= 5 && filteredCandidates.size == 2 && filteredCandidates.containsAll(listOf(0, hours.lastIndex))) {
                val midIndex = hours.lastIndex / 2
                if (midIndex != 0 && midIndex != hours.lastIndex) {
                    (filteredCandidates + midIndex).sorted()
                } else {
                    filteredCandidates
                }
            } else {
                filteredCandidates
            }

        for (index in finalCandidates) {
            if (index !in labelSignal.indices) continue
            if (index == 0 && suppressLeftEdgeLabel) {
                Log.d(TAG, "labelSkipped: idx=0 reason=nearby_lower_valley")
                continue
            }
            val cloudPct = labelSignal[index]
            val labelText = "$cloudPct%"
            val fontMetrics = paints.percentLabelPaint.fontMetrics
            val textAscent = if (fontMetrics != null && fontMetrics.ascent != 0f) fontMetrics.ascent else -paints.percentLabelPaint.textSize
            val textDescent = if (fontMetrics != null && fontMetrics.descent != 0f) fontMetrics.descent else paints.percentLabelPaint.textSize * 0.15f
            val textWidth = paints.percentLabelPaint.measureText(labelText)
            val centerX = points[index].first
            val y = points[index].second

            val isPeak = index == globalMaxIdx || (index > 0 && index < labelSignal.lastIndex &&
                labelSignal[index] > labelSignal[index - 1] && labelSignal[index] > labelSignal[index + 1])
            val isEndLabelCandidate = index == hours.lastIndex
            val isRisingAtEnd = isEndLabelCandidate &&
                index > 0 &&
                points[index].second < points[index - 1].second - HourlyGraphDefaults.TRENDING_THRESHOLD_PX
            val isFallingFromLeftEdge =
                index == 0 &&
                    points.size > 1 &&
                    points[1].second > points[0].second + HourlyGraphDefaults.TRENDING_THRESHOLD_PX
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

                val safeBottom = graphBottom - dpToPx(context, HourlyGraphDefaults.LABEL_SAFE_BOTTOM_INSET_DP)
                val lowCloudBelowOverflowPx = dpToPx(context, LOW_CLOUD_BELOW_OVERFLOW_DP)
                val allowBottomOverflow =
                    shouldAllowBottomOverflow(
                        cloudPct = cloudPct,
                        placeAbove = placeAbove,
                        isFallbackAttempt = isFallbackAttempt,
                    )
                val exceedsTop = bounds.top < 0f
                val actualExceedsBottom =
                    if (allowBottomOverflow) {
                        bounds.bottom > heightPx
                    } else {
                        bounds.bottom > safeBottom
                    }
                if (exceedsTop || actualExceedsBottom) {
                    Log.d(
                        TAG,
                        "labelRejected: idx=$index hour=$hourLabel value=$cloudPct% side=${if (placeAbove) "above" else "below"} " +
                            "attempt=${if (isFallbackAttempt) "fallback" else "preferred"} reason=out_of_bounds " +
                            "exceedsTop=$exceedsTop exceedsBottom=$actualExceedsBottom " +
                            "allowBottomOverflow=$allowBottomOverflow safeBottom=$safeBottom " +
                            "bounds=$bounds",
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
            val iconSizePx = dpToPx(context, HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            val windowSize = (points.size / WATERMARK_WINDOW_DIVISOR).coerceIn(WATERMARK_WINDOW_MIN, WATERMARK_WINDOW_MAX)
            val iconGap = dpToPx(context, WATERMARK_ICON_CURVE_GAP_DP)
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
                val verticalFractions = WATERMARK_VERT_FRACTIONS

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
                WatermarkPlacementDebug(
                    placed = placed,
                    candidateCenterIndex = placedCandidateIndex,
                ),
            )
        }

        if (missingHours > 0 && totalHours > 0) {
            drawMissingDataDiagnostic(
                context, canvas, widthPx, heightPx,
                missingHours = missingHours, totalHours = totalHours,
                missingDescription = missingDescription, missingReason = missingReason,
                labelScale = labelScale,
            )
        }

        return bitmap
    }

    /**
     * Draws a permanent "Cloud data missing …" indicator centered in the graph. Rendered
     * on every paint where the visible window has gaps so the user can tell the difference
     * between "actually clear" and "feed missing data." When [missingReason] is supplied
     * (typically pulled from recent NwsForecastMapper failure logs), it renders below the
     * main line in a dimmer style.
     */
    private fun drawMissingDataDiagnostic(
        context: Context,
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        missingHours: Int,
        totalHours: Int,
        missingDescription: String?,
        missingReason: String?,
        labelScale: Float,
    ) {
        val mainText = buildMissingDiagnosticText(missingHours, totalHours, missingDescription)
        val effectiveScale = labelScale.coerceAtLeast(MISSING_DIAG_MIN_LABEL_SCALE)
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(COLOR_MISSING_DIAG_TEXT)
            textSize = dpToPx(context, MISSING_DIAG_TEXT_SIZE_DP * effectiveScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(
                dpToPx(context, MISSING_DIAG_SHADOW_RADIUS_DP),
                0f,
                dpToPx(context, MISSING_DIAG_SHADOW_DY_DP),
                Color.parseColor(COLOR_MISSING_DIAG_SHADOW),
            )
        }
        val mainY = heightPx / 2f + mainPaint.textSize / 2f
        canvas.drawText(mainText, widthPx / 2f, mainY, mainPaint)

        if (!missingReason.isNullOrBlank()) {
            val reasonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_MISSING_DIAG_REASON_TEXT)
                textSize = dpToPx(context, MISSING_DIAG_REASON_TEXT_SIZE_DP * effectiveScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(
                    dpToPx(context, MISSING_DIAG_SHADOW_RADIUS_DP),
                    0f,
                    dpToPx(context, MISSING_DIAG_SHADOW_DY_DP),
                    Color.parseColor(COLOR_MISSING_DIAG_SHADOW),
                )
            }
            val reasonY = mainY + mainPaint.textSize * MISSING_DIAG_LINE_SPACING
            canvas.drawText("($missingReason)", widthPx / 2f, reasonY, reasonPaint)
        }
    }

    private fun buildMissingDiagnosticText(
        missingHours: Int,
        totalHours: Int,
        missingDescription: String?,
    ): String {
        if (missingHours >= totalHours) {
            return "Cloud data unavailable"
        }
        if (missingDescription.isNullOrBlank()) {
            val noun = if (missingHours == 1) "hr" else "hrs"
            return "Cloud data missing for $missingHours of $totalHours $noun"
        }
        return if (missingHours == 1) {
            "Cloud data missing at $missingDescription"
        } else {
            "Cloud data missing $missingDescription ($missingHours of $totalHours hrs)"
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun computeVerticalScale(values: List<Float>): VerticalScaleDebug {
        val visibleMax = values.maxOrNull()?.coerceIn(0f, 100f) ?: 0f
        val topScale =
            (visibleMax + TOP_SCALE_HEADROOM_PERCENT)
                .coerceIn(MIN_DYNAMIC_TOP_SCALE_PERCENT, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        return VerticalScaleDebug(
            visibleMax = visibleMax,
            topScale = topScale,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun mapCloudCoverToY(
        cloudCover: Float,
        graphBottom: Float,
        graphHeight: Float,
        topScale: Float,
    ): Float {
        val clampedValue = cloudCover.coerceIn(0f, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        val safeTopScale = topScale.coerceIn(MIN_DYNAMIC_TOP_SCALE_PERCENT, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        return graphBottom - graphHeight * (clampedValue / safeTopScale)
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        CloudCoverGraphStyle.dpToPx(context, dp)

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
    ): Boolean = false
}
