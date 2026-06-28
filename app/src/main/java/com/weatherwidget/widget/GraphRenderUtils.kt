package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.weatherwidget.shared.graph.CurveMath
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.NowIndicatorGeometry
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

internal object GraphRenderUtils {
    // Allocates two new Path objects per call. Caller invokes ~4x per render cycle (15-60 min interval),
    // producing ~8 short-lived Path objects. Acceptable for widget update frequency; if interval drops
    // below ~1 min, consider reusing Path instances via reset().
    fun buildSmoothCurveAndFillPaths(
        points: List<Pair<Float, Float>>,
        graphBottom: Float,
    ): Pair<Path, Path> {
        val curvePath = Path()
        val fillPath = Path()

        val segments = splitContiguousSegments(points)
        for (segment in segments) {
            if (segment.isEmpty()) continue
            curvePath.moveTo(segment[0].first, segment[0].second)
            fillPath.moveTo(segment[0].first, segment[0].second)

            if (segment.size > 1) {
                val tangents = computeTangents(segment)

                for (i in 0 until segment.size - 1) {
                    val cp1x = segment[i].first + tangents[i].first / 3f
                    val cp1y = segment[i].second + tangents[i].second / 3f
                    val cp2x = segment[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = segment[i + 1].second - tangents[i + 1].second / 3f
                    curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                    fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                }
            }

            fillPath.lineTo(segment.last().first, graphBottom)
            fillPath.lineTo(segment.first().first, graphBottom)
            fillPath.close()
        }

        return curvePath to fillPath
    }

    /**
     * Builds individual Path objects for each cubic segment between adjacent points.
     * Used for per-segment coloring (e.g., weather-adaptive forecast line colors).
     * Uses the same Catmull-Rom tangent logic as [buildSmoothCurveAndFillPaths].
     */
    fun buildPerSegmentPaths(points: List<Pair<Float, Float>>): List<Path> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<Path>()
        val segments = splitContiguousSegments(points)
        for (segment in segments) {
            if (segment.size < 2) continue
            val tangents = computeTangents(segment)
            for (i in 0 until segment.size - 1) {
                result.add(Path().apply {
                    moveTo(segment[i].first, segment[i].second)
                    val cp1x = segment[i].first + tangents[i].first / 3f
                    val cp1y = segment[i].second + tangents[i].second / 3f
                    val cp2x = segment[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = segment[i + 1].second - tangents[i + 1].second / 3f
                    cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                })
            }
        }
        return result
    }

    fun splitContiguousSegments(points: List<Pair<Float, Float>>): List<List<Pair<Float, Float>>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<List<Pair<Float, Float>>>()
        var current = mutableListOf<Pair<Float, Float>>()
        for (p in points) {
            if (p.first.isNaN() || p.second.isNaN()) {
                if (current.isNotEmpty()) {
                    segments.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(p)
            }
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * Computes monotone-aware tangents for a set of points.
     * These tangents ensure the cubic spline doesn't overshoot at peaks/valleys.
     * Delegates to the shared [CurveMath] so Android and desktop use identical curve math.
     */
    fun computeTangents(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
        CurveMath.computeTangents(points)

    /**
     * Evaluates the Y value of a monotone-aware cubic spline at a given fraction [0..1]
     * between two points p0 and p1 with tangents t0 and t1.
     */
    fun evaluateCubicY(
        p0y: Float,
        t0y: Float,
        p1y: Float,
        t1y: Float,
        t: Float,
    ): Float {
        val cp1y = p0y + t0y / 3f
        val cp2y = p1y - t1y / 3f
        
        val mt = 1f - t
        return mt * mt * mt * p0y +
               3f * mt * mt * t * cp1y +
               3f * mt * t * t * cp2y +
               t * t * t * p1y
    }

    fun <T> computeXForTime(
        targetTime: LocalDateTime,
        items: List<T>,
        points: List<Pair<Float, Float>>,
        hourWidth: Float,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        if (items.isEmpty() || points.isEmpty()) return null
        
        // Find the hour bucket
        val firstTime = dateTimeOf(items.first())
        val lastTime = dateTimeOf(items.last())
        
        if (targetTime.isBefore(firstTime) || targetTime.isAfter(lastTime)) return null
        
        // Find the index of the hour starting at or before targetTime
        val index = items.indexOfLast { !dateTimeOf(it).isAfter(targetTime) }
        if (index == -1 || index >= points.size) return null
        
        val basePointX = points[index].first
        val minutesOffset = Duration.between(dateTimeOf(items[index]), targetTime).toMinutes()
        
        return basePointX + (minutesOffset / 60f) * hourWidth
    }

    fun <T> computeNowX(
        items: List<T>,
        points: List<Pair<Float, Float>>,
        currentTime: LocalDateTime,
        hourWidth: Float,
        isCurrentHour: (T) -> Boolean,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        val currentHourIndex = items.indexOfFirst(isCurrentHour)
        if (currentHourIndex == -1 || currentHourIndex !in points.indices) return null

        val minutesOffset = Duration.between(dateTimeOf(items[currentHourIndex]), currentTime).toMinutes()
        return points[currentHourIndex].first + (minutesOffset / 60f) * hourWidth
    }

    /** Footer weather-icon size in px, derived from the hour-label text height. Shared so layout
     *  reservation and the draw pass below agree on a single value. See [HourlyGraphDefaults]. */
    /** A widget is "narrow" (tighter footer spacing, sparser markers) at or below the column
     *  threshold. numColumns <= 0 means unknown, treated as narrow (the conservative, tight case). */
    fun isNarrowWidget(numColumns: Int): Boolean =
        numColumns <= HourlyGraphDefaults.NARROW_WIDGET_MAX_COLUMNS

    /**
     * Places a date footer label horizontally: clamps a label that extends from [leftExtent] left to
     * [rightExtent] right of its desired [centerX] so it stays fully on a [widthPx]-wide canvas, and
     * returns the clamped center — or null (drop the label) when it can't be shown without clipping
     * the canvas (wider than the canvas) or overlapping the previously drawn date label, whose right
     * edge is [prevRightPx]. This keeps a label that merely needs a nudge (room to its right, e.g. the
     * left-edge day on a wide widget) while dropping one that would genuinely jam a neighbor (narrow
     * widgets). [minGapPx] is the minimum clear space required between adjacent date labels.
     */
    fun placeDateLabelCenter(
        centerX: Float,
        leftExtent: Float,
        rightExtent: Float,
        widthPx: Int,
        prevRightPx: Float,
        minGapPx: Float,
    ): Float? {
        if (leftExtent + rightExtent > widthPx) return null
        val clamped = centerX.coerceIn(leftExtent, widthPx - rightExtent)
        if (clamped - leftExtent < prevRightPx + minGapPx) return null
        return clamped
    }

    /** dp gap between the hour text and the icon in the inline footer group, by widget width class. */
    fun footerIconGapDp(numColumns: Int): Float =
        if (isNarrowWidget(numColumns)) HourlyGraphDefaults.FOOTER_ICON_GAP_NARROW_DP
        else HourlyGraphDefaults.FOOTER_ICON_GAP_WIDE_DP

    fun footerIconSize(hourLabelTextPaint: Paint): Float {
        // fontMetrics can be null (plain-JUnit stubs) or zeroed (Robolectric ShadowPaint); in either
        // case fall back to the explicitly-set textSize so the icon never collapses to 0.
        val fm = hourLabelTextPaint.fontMetrics
        val metricHeight = if (fm != null) fm.descent - fm.ascent else 0f
        val basis = if (metricHeight > 0f) metricHeight else hourLabelTextPaint.textSize
        return basis * HourlyGraphDefaults.FOOTER_ICON_TO_TEXT_RATIO
    }

    /**
     * Draw the hourly-graph footer. With [iconSize] > 0 and [drawIcon] supplied, each labeled hour
     * renders as a single inline row `<hour><a|p><icon>` (e.g. `3p☁`): the numeric part and
     * meridiem drawn as one centered string, then the weather icon to the right. The icon's [RectF] is handed to
     * [drawIcon] so the caller applies its own (condition-specific) tint and draws into it.
     * The icon is skipped on the last labeled hour to avoid crowding with its neighbor, except for
     * date labels ([isDateLabel]), which are one-per-day and far apart so the last day keeps its icon.
     * Hours where [hasIcon] is false fall back to the plain centered label.
     *
     * [isDateLabel] marks THREE_DAY per-day date labels ("Tue 23"): such a label is dropped entirely
     * when it would clip a canvas edge (rather than being clamped inward onto its neighbor).
     */
    private const val TAG = "GraphRenderUtils"

    private data class HourlyLayoutConfig(val spacing: Float, val drawIcons: Boolean)

    private fun <T> checkHourlyLabelOverlap(
        items: List<T>,
        points: List<Pair<Float, Float>>,
        widthPx: Int,
        spacing: Float,
        hourLabelTextPaint: Paint,
        gap: Float,
        showLabel: (T) -> Boolean,
        labelText: (T) -> String,
        iconSize: Float,
        hasIcon: (T) -> Boolean,
        isDateLabel: (T) -> Boolean,
        drawIcons: Boolean,
        minGapPx: Float,
        dateLabelGap: Float,
    ): Boolean {
        var lastLabeledIndex = -1
        var scanX = -1000f
        items.forEachIndexed { index, item ->
            if (index >= points.size) return@forEachIndexed
            val cx = points[index].first
            if (showLabel(item) && cx - scanX >= spacing) {
                lastLabeledIndex = index
                scanX = cx
            }
        }

        var lastHourLabelX = -1000f
        var lastDateLabelRight = Float.NEGATIVE_INFINITY
        var prevRight = Float.NEGATIVE_INFINITY

        items.forEachIndexed { index, item ->
            if (index >= points.size) return@forEachIndexed
            val centerX = points[index].first
            if (!showLabel(item) || centerX - lastHourLabelX < spacing) return@forEachIndexed

            val fullLabel = labelText(item)
            val dateLabel = isDateLabel(item)

            val inline = drawIcons && iconSize > 0f && hasIcon(item) &&
                (index != lastLabeledIndex || dateLabel)

            val left: Float
            val right: Float
            if (inline) {
                val hourText = fullLabel.dropLast(1)
                val meridiem = fullLabel.takeLast(1)
                val hourMerW = hourLabelTextPaint.measureText(hourText + meridiem)
                val leftExtent = hourMerW / 2f
                val rightExtent = hourMerW / 2f + gap + iconSize
                val center = if (dateLabel) {
                    placeDateLabelCenter(centerX, leftExtent, rightExtent, widthPx, lastDateLabelRight, dateLabelGap)
                        ?: return@forEachIndexed
                } else {
                    centerX.coerceIn(leftExtent, widthPx - rightExtent)
                }
                left = center - leftExtent
                right = center + rightExtent
                if (dateLabel) lastDateLabelRight = center + rightExtent
            } else {
                val textWidth = hourLabelTextPaint.measureText(fullLabel)
                val half = textWidth / 2f
                val clampedX = if (dateLabel) {
                    placeDateLabelCenter(centerX, half, half, widthPx, lastDateLabelRight, dateLabelGap)
                        ?: return@forEachIndexed
                } else {
                    centerX.coerceIn(half, widthPx - half)
                }
                left = clampedX - half
                right = clampedX + half
                if (dateLabel) lastDateLabelRight = clampedX + half
            }

            if (prevRight != Float.NEGATIVE_INFINITY && left < prevRight + minGapPx) {
                return true
            }
            prevRight = right
            lastHourLabelX = centerX
        }
        return false
    }

    /**
     * Draw the hourly-graph footer. With [iconSize] > 0 and [drawIcon] supplied, each labeled hour
     * renders as a single inline row `<hour><a|p><icon>` (e.g. `3p☁`): the numeric part and
     * meridiem drawn as one centered string, then the weather icon to the right. The icon's [RectF] is handed to
     * [drawIcon] so the caller applies its own (condition-specific) tint and draws into it.
     * The icon is skipped on the last labeled hour to avoid crowding with its neighbor, except for
     * date labels ([isDateLabel]), which are one-per-day and far apart so the last day keeps its icon.
     * Hours where [hasIcon] is false fall back to the plain centered label.
     *
     * [isDateLabel] marks THREE_DAY per-day date labels ("Tue 23"): such a label is dropped entirely
     * when it would clip a canvas edge (rather than being clamped inward onto its neighbor).
     */
    fun <T> drawHourLabels(
        canvas: Canvas,
        items: List<T>,
        points: List<Pair<Float, Float>>,
        widthPx: Int,
        heightPx: Int,
        minHourLabelSpacing: Float,
        hourLabelTextPaint: Paint,
        dpToPx: (Float) -> Float,
        showLabel: (T) -> Boolean,
        labelText: (T) -> String,
        iconSize: Float = 0f,
        iconTextGapDp: Float = 0f,
        hasIcon: (T) -> Boolean = { false },
        isDateLabel: (T) -> Boolean = { false },
        drawIcon: ((index: Int, iconRect: RectF) -> Unit)? = null,
    ) {
        val bottomInset = dpToPx(HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)
        val gap = dpToPx(iconTextGapDp)
        val minGapPx = dpToPx(3f)
        val dateLabelGap = dpToPx(HourlyGraphDefaults.DATE_LABEL_MIN_GAP_DP)

        // Find optimal spacing and drawIcons configuration to prevent overlap
        val configs = listOf(
            HourlyLayoutConfig(minHourLabelSpacing, true),
            HourlyLayoutConfig(minHourLabelSpacing, false),
            HourlyLayoutConfig(minHourLabelSpacing * 1.4f, true),
            HourlyLayoutConfig(minHourLabelSpacing * 1.4f, false),
            HourlyLayoutConfig(minHourLabelSpacing * 1.8f, true),
            HourlyLayoutConfig(minHourLabelSpacing * 1.8f, false),
            HourlyLayoutConfig(minHourLabelSpacing * 2.2f, false)
        )

        var selectedConfig = configs.first()
        for (config in configs) {
            val hasOverlap = checkHourlyLabelOverlap(
                items = items,
                points = points,
                widthPx = widthPx,
                spacing = config.spacing,
                hourLabelTextPaint = hourLabelTextPaint,
                gap = gap,
                showLabel = showLabel,
                labelText = labelText,
                iconSize = iconSize,
                hasIcon = hasIcon,
                isDateLabel = isDateLabel,
                drawIcons = config.drawIcons,
                minGapPx = minGapPx,
                dateLabelGap = dateLabelGap
            )
            if (!hasOverlap) {
                selectedConfig = config
                break
            }
        }

        val finalSpacing = selectedConfig.spacing
        val finalDrawIcons = selectedConfig.drawIcons

        Log.d(TAG, "drawHourLabels: selected spacing=$finalSpacing, drawIcons=$finalDrawIcons (original spacing=$minHourLabelSpacing, items=${items.size})")

        // A single baseline shared by every label keeps the row flat. When icons are present the
        // row occupies an `iconSize`-tall band at the bottom and the text is vertically centered
        // on the icon; otherwise the text sits just above the bottom edge.
        val iconBottom = heightPx - bottomInset
        val iconTop = iconBottom - iconSize
        val baselineY = if (iconSize > 0f && finalDrawIcons) {
            val centerY = (iconTop + iconBottom) / 2f
            val fm = hourLabelTextPaint.fontMetrics
            val textCenterOffset = if (fm != null) (fm.ascent + fm.descent) / 2f else 0f
            centerY - textCenterOffset
        } else {
            heightPx - bottomInset
        }

        // Label density is governed by the upstream showLabel cadence (zoom.labelInterval) plus
        // minHourLabelSpacing as a lower bound; we draw an inline group for every labeled hour.
        // Pre-scan to find the last labeled index so we can skip its icon (avoids crowding).
        var lastLabeledIndex = -1
        var scanX = -1000f
        items.forEachIndexed { index, item ->
            if (index >= points.size) return@forEachIndexed
            val cx = points[index].first
            if (showLabel(item) && cx - scanX >= finalSpacing) {
                lastLabeledIndex = index
                scanX = cx
            }
        }
        var lastHourLabelX = -1000f
        // Right edge of the last drawn date label, for adjacent-overlap detection (date footer only).
        var lastDateLabelRight = Float.NEGATIVE_INFINITY

        items.forEachIndexed { index, item ->
            if (index >= points.size) return@forEachIndexed
            val centerX = points[index].first
            if (!showLabel(item) || centerX - lastHourLabelX < finalSpacing) return@forEachIndexed

            val fullLabel = labelText(item)

            val dateLabel = isDateLabel(item)

            // The last-labeled icon is normally skipped to keep crowded time-of-day labels from
            // colliding near the right edge. Date labels are one-per-day and far apart, so the last
            // day keeps its icon (otherwise the rightmost day, e.g. "Fri 26", loses its indicator).
            val inline = finalDrawIcons && iconSize > 0f && drawIcon != null && hasIcon(item) &&
                (index != lastLabeledIndex || dateLabel)

            if (inline) {
                val hourText = fullLabel.dropLast(1)
                val meridiem = fullLabel.takeLast(1)
                val hourMerW = hourLabelTextPaint.measureText(hourText + meridiem)
                // Draw hour+meridiem as a single centered string (e.g. "3p"), then place the
                // icon to the right. The combined drawText naturally centers the hour around
                // the meridiem letter.
                val leftExtent = hourMerW / 2f
                val rightExtent = hourMerW / 2f + gap + iconSize
                // Date labels: clamp inward and drop only when they'd clip the canvas or jam the
                // neighbor day (overlap-aware). Time-of-day labels keep the plain edge clamp.
                val center = if (dateLabel) {
                    placeDateLabelCenter(centerX, leftExtent, rightExtent, widthPx, lastDateLabelRight, dateLabelGap)
                        ?: return@forEachIndexed
                } else {
                    centerX.coerceIn(leftExtent, widthPx - rightExtent)
                }
                val textX = center
                val iconLeft = center + hourMerW / 2f + gap
                // hourLabelTextPaint is Align.CENTER, so drawText x is the string's center.
                canvas.drawText(hourText + meridiem, textX, baselineY, hourLabelTextPaint)
                // Skip the icon if it would overflow the right edge (avoids the last two
                // labels crowding each other when the rightmost group can't fit its icon).
                if (iconLeft + iconSize <= widthPx) {
                    drawIcon(index, RectF(iconLeft, iconTop, iconLeft + iconSize, iconBottom))
                }
                if (dateLabel) lastDateLabelRight = center + rightExtent
            } else {
                val textWidth = hourLabelTextPaint.measureText(fullLabel)
                val half = textWidth / 2f
                val clampedX = if (dateLabel) {
                    placeDateLabelCenter(centerX, half, half, widthPx, lastDateLabelRight, dateLabelGap)
                        ?: return@forEachIndexed
                } else {
                    centerX.coerceIn(half, widthPx - half)
                }
                canvas.drawText(fullLabel, clampedX, baselineY, hourLabelTextPaint)
                if (dateLabel) lastDateLabelRight = clampedX + half
            }
            lastHourLabelX = centerX
        }
    }

    data class NowLabelResult(val labelY: Float, val bounds: RectF)

    private fun GraphRect.toRectF(): RectF = RectF(left, top, right, bottom)

    data class DayLabelPlacement(
        val side: String,
        val text: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,
        val isToday: Boolean,
        val bounds: RectF,
    )

    fun computeNowLabelBounds(
        nowX: Float,
        graphTop: Float,
        graphHeight: Float,
        textWidth: Float,
        fontAscent: Float,
        fontDescent: Float,
        drawnBounds: List<RectF> = emptyList(),
        dpToPx: (Float) -> Float,
    ): NowLabelResult? {
        // Geometry lives in shared NowIndicatorGeometry so Android and desktop stay in lockstep;
        // this adapter converts RectF<->GraphRect and exposes the Android baseline (labelY).
        val placement = NowIndicatorGeometry.computeNowLabel(
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            labelWidth = textWidth,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            drawnBounds = drawnBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            dpToPx = dpToPx,
        ) ?: return null
        return NowLabelResult(placement.baselineY, placement.box.toRectF())
    }

    fun drawNowLine(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
    ) {
        if (nowX == null) return
        val line = NowIndicatorGeometry.computeNowLine(graphTop, graphHeight)
        canvas.drawLines(floatArrayOf(nowX, line.lineTop, nowX, line.lineBottom), currentTimePaint)
    }

    fun drawNowIndicator(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
        nowLabelTextPaint: Paint,
        drawnBounds: List<RectF> = emptyList(),
        drawLine: Boolean = true,
        dpToPx: (Float) -> Float,
    ) {
        if (nowX == null) return

        if (drawLine) {
            drawNowLine(canvas, nowX, graphTop, graphHeight, currentTimePaint)
        }

        val text = "NOW"
        val textWidth = nowLabelTextPaint.measureText(text)
        val fm = nowLabelTextPaint.fontMetrics ?: Paint.FontMetrics().apply {
            ascent = -nowLabelTextPaint.textSize
            descent = nowLabelTextPaint.textSize * 0.2f
        }
        val result = computeNowLabelBounds(
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            textWidth = textWidth,
            fontAscent = fm.ascent,
            fontDescent = fm.descent,
            drawnBounds = drawnBounds,
            dpToPx = dpToPx
        )
        if (result != null) {
            canvas.drawText(text, nowX, result.labelY, nowLabelTextPaint)
        }
    }

    /**
     * Computes final placements for the left and right day-of-week labels on an hourly graph
     * without drawing them.
     *
     * The function mirrors the placement rules used by [drawDayLabels]: it scans from the top
     * of the graph downward, avoids collisions with existing text/icon bounds and with the other
     * day label, and falls back to a bottom placement if no collision-free graph position exists.
     *
     * Use this when later layout decisions, such as watermark or secondary annotation placement,
     * need to reserve space for day labels before the render pass draws them.
     */
    fun computeDayLabelPlacements(
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelFontMetrics: Pair<Float, Float>,
        todayLabelFontMetrics: Pair<Float, Float>,
        measureDayText: (String, Boolean) -> Float,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
    ): List<DayLabelPlacement> {
        val fm = Paint.FontMetrics().apply {
            ascent = dayLabelFontMetrics.first
            descent = dayLabelFontMetrics.second
        }

        fun dayBounds(x: Float, y: Float, textWidth: Float, fontMetrics: Paint.FontMetrics): RectF =
            RectF(x - textWidth / 2f, y + fontMetrics.ascent, x + textWidth / 2f, y + fontMetrics.descent)

        val placedBounds = mutableListOf<RectF>()

        fun collides(bounds: RectF): Boolean =
            drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
                drawnIconBounds.any { RectF.intersects(it, bounds) } ||
                placedBounds.any { RectF.intersects(it, bounds) }

        data class DayCandidate(val side: String, val date: LocalDate, val x: Float, val text: String)

        val candidates = listOf(
            DayCandidate(side = "LEFT", date = leftDate, x = leftX, text = leftText),
            DayCandidate(side = "RIGHT", date = rightDate, x = rightX, text = rightText),
        )

        val yFractions = (5..92 step 2).map { it / 100f }
        val graphHeight = graphBottom - graphTop
        val placements = mutableListOf<DayLabelPlacement>()

        for (candidate in candidates) {
            val isToday = candidate.date == today
            val fontMetrics = if (isToday) {
                Paint.FontMetrics().apply {
                    ascent = todayLabelFontMetrics.first
                    descent = todayLabelFontMetrics.second
                }
            } else fm
            val textWidth = measureDayText(candidate.text, isToday)

            var placement: DayLabelPlacement? = null
            for (yFrac in yFractions) {
                val y = graphTop + graphHeight * yFrac
                val bounds = dayBounds(candidate.x, y, textWidth, fontMetrics)
                if (!collides(bounds)) {
                    placement = DayLabelPlacement(
                        side = candidate.side,
                        text = candidate.text,
                        date = candidate.date,
                        x = candidate.x,
                        y = y,
                        placement = "yFrac=$yFrac",
                        isToday = isToday,
                        bounds = bounds,
                    )
                    break
                }
            }

            if (placement == null) {
                val y = heightPx - dpToPx(14f)
                placement = DayLabelPlacement(
                    side = candidate.side,
                    text = candidate.text,
                    date = candidate.date,
                    x = candidate.x,
                    y = y,
                    placement = "BOTTOM_FALLBACK",
                    isToday = isToday,
                    bounds = dayBounds(candidate.x, y, textWidth, fontMetrics),
                )
            }

            placements.add(placement)
            placedBounds.add(placement.bounds)
        }

        return placements
    }

    /**
     * Applies a 3-point weighted moving average [0.25, 0.5, 0.25] to smooth out
     * "stair-step" data plateaus. Multiple iterations create a progressively
     * smoother, more fluid curve.
     */
    fun smoothValues(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        var current = values

        repeat(iterations) {
            current = smoothValuesOnePass(current)
        }

        return current
    }

    /**
     * Applies smoothing while preserving the global maximum, global minimum, start point, and end point.
     * This ensures the daily high/low points and graph anchors remain accurate while providing
     * smooth transitions between them.
     */
    fun smoothValuesPreservingGlobalExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        // Find global max, global min, start, and end indices before smoothing
        val globalMaxIndex = values.indices.maxByOrNull { values[it] } ?: 0
        val globalMinIndex = values.indices.minByOrNull { values[it] } ?: 0
        val preservedIndices = setOf(0, values.lastIndex, globalMaxIndex, globalMinIndex)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    /**
     * Applies smoothing with configurable extrema preservation.
     * Useful when you want to preserve some features (like peaks) but not others (like dips).
     */
    fun smoothValuesPreservingExtrema(
        values: List<Float>,
        iterations: Int = 1,
        preserveGlobalMax: Boolean = true,
        preserveGlobalMin: Boolean = true,
        preserveStart: Boolean = true,
        preserveEnd: Boolean = true,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        val globalMaxIndex = values.indices.maxByOrNull { values[it] } ?: 0
        val globalMinIndex = values.indices.minByOrNull { values[it] } ?: 0

        val preservedIndices = mutableSetOf<Int>()
        if (preserveStart) preservedIndices.add(0)
        if (preserveEnd) preservedIndices.add(values.lastIndex)
        if (preserveGlobalMax) preservedIndices.add(globalMaxIndex)
        if (preserveGlobalMin) preservedIndices.add(globalMinIndex)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    /**
     * Applies smoothing while preserving ALL local maxima and minima, as well as the
     * start and end points. This prevents "melting" local peaks/valleys into their
     * neighbors, which is critical for highly dynamic data (like cloud cover or precip)
     * where a local 80% peak shouldn't be averaged down just because it's not the
     * global daily maximum.
     */
    fun smoothValuesPreservingAllExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        val intValues = values.map { it.roundToInt() }
        val maxima = findLocalExtremaIndices(intValues, isMax = true)
        val minima = findLocalExtremaIndices(intValues, isMax = false)
        
        val preservedIndices = mutableSetOf(0, values.lastIndex)
        preservedIndices.addAll(maxima)
        preservedIndices.addAll(minima)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    private fun smoothValuesOnePass(values: List<Float>): List<Float> {
        val smoothed = MutableList(values.size) { 0f }
        for (i in values.indices) {
            val prev = if (i > 0) values[i - 1] else values[i]
            val curr = values[i]
            val next = if (i < values.lastIndex) values[i + 1] else values[i]

            // Weighted average: 25% prev, 50% current, 25% next
            smoothed[i] = prev * 0.25f + curr * 0.5f + next * 0.25f
        }
        return smoothed
    }

    private fun smoothValuesWithPreservedAnchors(
        values: List<Float>,
        iterations: Int,
        preservedIndices: Set<Int>,
    ): List<Float> {
        var current = values

        repeat(iterations) {
            val smoothed = smoothValuesOnePass(current).toMutableList()
            preservedIndices.forEach { smoothed[it] = values[it] }
            current = smoothed
        }

        return current
    }

    /**
     * Draws a "last fetch dot" on the graph curve, with optional staleness age label (below the
     * dot) and value label (right/left/top of the dot).
     *
     * The caller is responsible for computing [fetchX], [fetchY], [valueLabel], and [ageMinutes].
     * Pass [ageMinutes] as null when the zoomed-in label display should be suppressed (i.e. the
     * window is wider than 12 hours).
     */
    fun drawFetchDot(
        context: Context,
        canvas: Canvas,
        fetchX: Float,
        fetchY: Float,
        valueLabel: String,
        ageMinutes: Long?,
        bitmapScale: Float,
        widthPx: Int,
        heightPx: Int,
        dotRadiusDp: Float = 2.5f,
        dpToPx: (Float) -> Float,
    ) {
        val dotRadius = dpToPx(dotRadiusDp * bitmapScale.coerceIn(0.5f, 1f))
        val clampedFetchX = fetchX.coerceIn(dotRadius, widthPx.toFloat() - dotRadius)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius, dotPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius, ringPaint)

        val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(0.5f)
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius + 0.5f, outerRingPaint)

        if (ageMinutes != null && ageMinutes >= 0) {
            val ageLabel = if (ageMinutes >= 60) {
                val h = ageMinutes / 60
                val m = ageMinutes % 60
                if (m > 0) "${h}h ${m}m" else "${h}h"
            } else "${ageMinutes}m"

            val labelScale = bitmapScale.coerceIn(0.5f, 1f)
            val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BBFFFFFF")
                textSize = dpToPx(11f * labelScale)
                textAlign = Paint.Align.LEFT
                setShadowLayer(dpToPx(1f), 0f, dpToPx(0.5f), Color.parseColor("#88000000"))
            }
            val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                textSize = dpToPx(8f * labelScale)
                textAlign = Paint.Align.CENTER
                setShadowLayer(dpToPx(1f), 0f, dpToPx(0.5f), Color.parseColor("#88000000"))
            }

            // 1. Draw Staleness (Age) - Underneath the dot
            val ageY = fetchY + dotRadius + dpToPx(4f * labelScale) - stalenessTextPaint.ascent()
            if (ageY + stalenessTextPaint.descent() <= heightPx) {
                canvas.drawText(ageLabel, clampedFetchX, ageY, stalenessTextPaint)
            }

            // 2. Draw Value - Multi-directional placement
            val valueWidth = valueTextPaint.measureText(valueLabel)
            val valueHeight = valueTextPaint.textSize
            val sideGap = dpToPx(4f * labelScale)

            // Attempt 1: Right
            var drawnValue = false
            val rightX = clampedFetchX + dotRadius + sideGap
            if (rightX + valueWidth <= widthPx) {
                valueTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(valueLabel, rightX, fetchY + valueHeight / 3f, valueTextPaint)
                drawnValue = true
            }

            // Attempt 2: Left
            if (!drawnValue) {
                val leftX = clampedFetchX - dotRadius - sideGap
                if (leftX - valueWidth >= 0) {
                    valueTextPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(valueLabel, leftX, fetchY + valueHeight / 3f, valueTextPaint)
                    drawnValue = true
                }
            }

            // Attempt 3: Top
            if (!drawnValue) {
                val topY = fetchY - dotRadius - dpToPx(2f * labelScale)
                if (topY + valueTextPaint.ascent() >= 0) {
                    valueTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(valueLabel, clampedFetchX, topY, valueTextPaint)
                }
            }
        }
    }

    /**
     * Draws day-of-week labels at the left and right edges of an hourly graph.
     * Placement cascades: TOP → MIDDLE → BOTTOM, with collision detection against
     * existing label bounds, icon bounds, and previously placed day labels.
     *
     * Returns the list of [RectF] bounds for all placed day labels (for callers that need it).
     */
    fun drawDayLabels(
        context: Context,
        canvas: Canvas,
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelTextPaint: Paint,
        todayDayLabelPaint: Paint,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
        onDayLabelPlaced: ((side: String, text: String, date: LocalDate, x: Float, y: Float, placement: String, isToday: Boolean) -> Unit)? = null,
    ) {
        val dayLabelFontMetrics = dayLabelTextPaint.fontMetrics?.let { it.ascent to it.descent }
            ?: (-dayLabelTextPaint.textSize to dayLabelTextPaint.textSize * 0.2f)
        val todayLabelFontMetrics = todayDayLabelPaint.fontMetrics?.let { it.ascent to it.descent }
            ?: (-todayDayLabelPaint.textSize to todayDayLabelPaint.textSize * 0.2f)
        val measureDayText: (String, Boolean) -> Float = { text, isToday ->
            (if (isToday) todayDayLabelPaint else dayLabelTextPaint).measureText(text)
        }
        val placements = computeDayLabelPlacements(
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftX,
            rightX = rightX,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelFontMetrics = dayLabelFontMetrics,
            todayLabelFontMetrics = todayLabelFontMetrics,
            measureDayText = measureDayText,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = dpToPx,
        )

        for (placement in placements) {
            val paint = if (placement.isToday) todayDayLabelPaint else dayLabelTextPaint
            canvas.drawText(placement.text, placement.x, placement.y, paint)
            Log.d("DayLabel", "${placement.side} \"${placement.text}\" at y=${placement.y} ${placement.placement} iconBounds=${drawnIconBounds.size} labelBounds=${drawnLabelBounds.size}")
            onDayLabelPlaced?.invoke(
                placement.side,
                placement.text,
                placement.date,
                placement.x,
                placement.y,
                placement.placement,
                placement.isToday,
            )
        }
    }

    /**
     * Finds local maxima and minima indices in a series, correctly handling plateaus
     * by marking the center of identical runs as the extremum.
     */
    fun findLocalExtremaIndices(
        values: List<Int>,
        isMax: Boolean,
    ): Set<Int> {
        if (values.size < 3) return emptySet()

        val extrema = mutableSetOf<Int>()
        var i = 1
        while (i < values.lastIndex) {
            val current = values[i]
            val prev = values[i - 1]

            val isPotential = if (isMax) current > prev else current < prev

            if (isPotential) {
                // We found the start of a potential peak/valley.
                // Find how long this plateau lasts.
                var j = i
                while (j < values.lastIndex && values[j + 1] == current) {
                    j++
                }

                // Now check the exit condition of the plateau
                if (j < values.lastIndex) {
                    val next = values[j + 1]
                    val isExtremum = if (isMax) next < current else next > current
                    if (isExtremum) {
                        // Mark the middle (or first) of the plateau as the extremum
                        extrema.add(i + (j - i) / 2)
                    }
                }
                i = j // Skip to end of plateau
            }
            i++
        }
        return extrema
    }

    fun drawErrorWatermark(
        canvas: Canvas,
        width: Float,
        height: Float,
        density: Float,
        sourceLabel: String? = null,
        errorCode: String? = null,
        failureTimeMs: Long? = null,
    ) {
        // A translucent "glass" pill near the top center, naming the failing source so the
        // user can tell which data feed is stale (e.g. "Silurian" vs "NWS"). Drawn last, on top.
        val label = sourceLabel?.takeIf { it.isNotBlank() }?.let { "${it.uppercase()} UPDATES FAILING" }
            ?: "UPDATES FAILING"
        val text = "⚠ $label" // warning triangle prefix

        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#FFFF5A5A") // bright coral-red, full opacity
            textSize = 15f * density
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                letterSpacing = 0.08f
            }
        }

        val codeStr = errorCode?.let { humanReadableErrorCode(it) }
        val timeStr = failureTimeMs?.let { formatFailureTime(it) }
        val codeLine = when {
            codeStr != null && timeStr != null -> "$codeStr · $timeStr"
            codeStr != null -> codeStr
            timeStr != null -> timeStr
            else -> null
        }
        val codePaint = if (codeLine != null) Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#B2FF5A5A") // coral-red at 70% opacity
            textSize = 13f * density
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        } else null

        val hPad = 12f * density
        val vPad = 6f * density
        val textWidth = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        val codeWidth = codePaint?.measureText(codeLine) ?: 0f
        val codeFm = codePaint?.fontMetrics
        val codeHeight = if (codeFm != null) codeFm.descent - codeFm.ascent else 0f
        val codeGap = if (codeLine != null) 2f * density else 0f

        val pillWidth = (maxOf(textWidth, codeWidth) + hPad * 2f).coerceAtMost(width - 8f * density)
        val pillHeight = textHeight + codeHeight + codeGap + vPad * 2f
        val centerX = width / 2f
        val pillTop = 8f * density
        val pillRect = RectF(
            centerX - pillWidth / 2f,
            pillTop,
            centerX + pillWidth / 2f,
            pillTop + pillHeight,
        )
        val radius = pillHeight / 2f

        val bgPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#8C1A0E0E") // ~55% opacity dark, faint warm tint
        }
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.parseColor("#66FF5A5A") // soft coral outline
        }
        canvas.drawRoundRect(pillRect, radius, radius, bgPaint)
        canvas.drawRoundRect(pillRect, radius, radius, borderPaint)

        val contentTop = pillRect.top + vPad
        val mainBaselineY = contentTop - fm.ascent
        canvas.drawText(text, centerX, mainBaselineY, textPaint)

        if (codeLine != null && codePaint != null && codeFm != null) {
            val codeBaselineY = mainBaselineY + fm.descent + codeGap - codeFm.ascent
            canvas.drawText(codeLine, centerX, codeBaselineY, codePaint)
        }
    }

    private fun formatFailureTime(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        val nowDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val nowYear = cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = epochMs
        val failDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val failYear = cal.get(java.util.Calendar.YEAR)
        val fmt = if (failYear == nowYear && failDay == nowDay) {
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        } else {
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        }
        return fmt.format(java.util.Date(epochMs))
    }

    private fun humanReadableErrorCode(code: String): String = when (code) {
        "HTTP_400" -> "400 Bad Request"
        "HTTP_401" -> "401 Unauthorized"
        "HTTP_403" -> "403 Forbidden"
        "HTTP_404" -> "404 Not Found"
        "HTTP_422" -> "422 Unprocessable"
        "HTTP_429" -> "429 Rate Limited"
        "ACCESS_ERROR" -> "Access Error"
        "DNS_ERROR" -> "DNS Error"
        "CONN_REFUSED" -> "Connection Refused"
        "TIMEOUT" -> "Timed Out"
        "SSL_ERROR" -> "SSL Error"
        "SOCKET_ERROR" -> "Socket Error"
        else -> when {
            code.startsWith("HTTP_5") -> "${code.removePrefix("HTTP_")} Server Error"
            code.startsWith("HTTP_") -> "HTTP ${code.removePrefix("HTTP_")}"
            else -> code
        }
    }

    /**
     * Draws a single footer weather icon at [iconRect] with the standard day/night/twilight/sunny
     * tint (rainy/mixed icons keep their own colors). Shared by the cloud-cover and precipitation
     * renderers, whose footer-icon draw bodies were identical apart from each one's own per-icon
     * bookkeeping (which stays at the call site). The caller guarantees [iconRes] is non-null.
     */
    fun drawHourIcon(
        context: Context,
        canvas: Canvas,
        iconRes: Int,
        iconRect: RectF,
        isRainy: Boolean,
        isMixed: Boolean,
        isNight: Boolean,
        isTwilight: Boolean,
        isSunny: Boolean,
    ) {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes) ?: return
        drawable.setBounds(
            iconRect.left.toInt(), iconRect.top.toInt(),
            iconRect.right.toInt(), iconRect.bottom.toInt(),
        )
        if (!isRainy && !isMixed) {
            val iconTint = when {
                isNight -> HourlyGraphDefaults.ICON_TINT_NIGHT
                isTwilight -> HourlyGraphDefaults.ICON_TINT_TWILIGHT
                isSunny -> HourlyGraphDefaults.ICON_TINT_SUNNY
                else -> HourlyGraphDefaults.ICON_TINT_DEFAULT
            }
            drawable.setTint(iconTint)
        }
        drawable.draw(canvas)
    }

    /**
     * Footer day-of-week endpoint labels: today plus the left/right edge dates and their short day
     * names. Identical setup in the cloud-cover and precipitation renderers (each then draws them
     * its own way), so the derivation lives here.
     */
    data class DayLabelEndpoints(
        val today: LocalDate,
        val leftDate: LocalDate,
        val rightDate: LocalDate,
        val leftText: String,
        val rightText: String,
    )

    fun dayLabelEndpoints(
        firstDateTime: LocalDateTime,
        lastDateTime: LocalDateTime,
        currentTime: LocalDateTime,
    ): DayLabelEndpoints = DayLabelEndpoints(
        today = currentTime.toLocalDate(),
        leftDate = firstDateTime.toLocalDate(),
        rightDate = lastDateTime.toLocalDate(),
        leftText = firstDateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
        rightText = lastDateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
    )
}

