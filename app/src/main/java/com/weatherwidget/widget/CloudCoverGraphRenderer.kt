package com.weatherwidget.widget

import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.ValueLabelEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudCoverGraphRenderer {

    private const val TAG = "CloudCoverGraph"
    // Retained for shouldAllowBottomOverflow (unit-tested); candidate/placement tuning now lives in
    // the shared ValueLabelEngine.Config.cloud().
    private const val LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT = 55

    private const val GRAPH_TOP_PADDING_DP = 38f
    private const val GRAPH_BOTTOM_PADDING_DP = 3f
    private const val TOP_SCALE_HEADROOM_PERCENT = 12f
    private const val MIN_DYNAMIC_TOP_SCALE_PERCENT = 85f
    private const val MAX_DYNAMIC_TOP_SCALE_PERCENT = 100f
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
        val isDateLabel: Boolean = false,
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
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
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
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
            }
            return bitmap
        }

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density
        val tallGraph = heightDp >= HourlyGraphDefaults.TALL_GRAPH_HEIGHT_DP
        val labelScale = bitmapScale.coerceAtMost(1f)

        // --- Paints (gray color scheme, cached by density + height band) ---
        val paints = ensurePaints(context, tallGraph, labelScale)

        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP * labelScale)
        val hasHourlyIcons = hours.any { it.iconRes != null }
        val showHourlyIcons = hasHourlyIcons && widthPx >= HourlyGraphDefaults.MIN_ICON_GRAPH_WIDTH_PX
        // Inline footer row sized to the hour-label text (see GraphRenderUtils.footerIconSize).
        val footerIconSize = GraphRenderUtils.footerIconSize(paints.hourLabelTextPaint)
        val labelHeight = dpToPx(context, HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)
        val bottomPadding = dpToPx(context, GRAPH_BOTTOM_PADDING_DP * labelScale)
        val bottomInset = dpToPx(context, HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - footerIconSize - bottomInset
            } else {
                heightPx - labelHeight - bottomPadding
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
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

        // Draw Now Line early so it's behind all labels and curves (lowest z-order)
        val nowX = GraphRenderUtils.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime }
        )
        GraphRenderUtils.drawNowLine(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = paints.currentTimePaint,
        )

        canvas.drawPath(fillPath, paints.gradientPaint)
        canvas.drawPath(curvePath, paints.curvePaint)

        // --- Hour labels and icons ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val drawnIconBounds = mutableListOf<RectF>()

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
            iconSize = footerIconSize,
            iconTextGapDp = GraphRenderUtils.footerIconGapDp(numColumns),
            hasIcon = { showHourlyIcons && it.iconRes != null },
            isDateLabel = { it.isDateLabel },
        ) { index, iconRect ->
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawHourLabels
            drawnIconBounds.add(iconRect)
            GraphRenderUtils.drawHourIcon(
                context, canvas, iconRes, iconRect,
                isRainy = hour.isRainy, isMixed = hour.isMixed,
                isNight = hour.isNight, isTwilight = hour.isTwilight, isSunny = hour.isSunny,
            )
        }

        // --- Percentage labels (peak / dip / start / end) via the shared ValueLabelEngine ---
        val labelSignal = smoothedValues.map { it.roundToInt().coerceIn(0, 100) }
        val drawnLabelBounds = mutableListOf<RectF>()
        val cloudLabelFm = paints.percentLabelPaint.fontMetrics
        val cloudLabelAscent = if (cloudLabelFm != null && cloudLabelFm.ascent != 0f) cloudLabelFm.ascent else -paints.percentLabelPaint.textSize
        val cloudLabelDescent = if (cloudLabelFm != null && cloudLabelFm.descent != 0f) cloudLabelFm.descent else paints.percentLabelPaint.textSize * 0.15f
        ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = points.map { ValueLabelEngine.GraphPoint(it.first, it.second) },
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx.toFloat(), heightPx.toFloat()),
            config = ValueLabelEngine.Config.cloud(),
            measureText = { paints.percentLabelPaint.measureText(it) },
            textAscent = cloudLabelAscent,
            textDescent = cloudLabelDescent,
            dpToPx = { dpToPx(context, it) },
            drawnIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            numColumns = numColumns,
        ).forEach { p ->
            canvas.drawText(p.text, p.centerX, p.baselineY, paints.percentLabelPaint)
            drawnLabelBounds.add(RectF(p.box.left, p.box.top, p.box.right, p.box.bottom))
            onLabelPlaced?.invoke(
                LabelPlacementDebug(
                    index = p.index,
                    cloudCover = labelSignal[p.index],
                    placedAbove = p.placedAbove,
                    isGlobalMax = p.isGlobalMax,
                    isGlobalMin = p.isGlobalMin,
                ),
            )
        }

        // --- Day labels ---
        val (today, leftDate, rightDate, leftText, rightText) =
            GraphRenderUtils.dayLabelEndpoints(hours.first().dateTime, hours.last().dateTime, currentTime)

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
            drawLine = false,
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

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
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
