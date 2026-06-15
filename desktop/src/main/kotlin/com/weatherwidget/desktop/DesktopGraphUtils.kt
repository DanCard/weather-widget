package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.CurveMath
import com.weatherwidget.shared.graph.ForecastEvolutionStyle
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.NowIndicatorGeometry
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import java.time.format.TextStyle as JavaTextStyle

/**
 * Shared utilities for desktop hourly graph renderers (Temperature, Precipitation, CloudCover).
 * Eliminates copy-paste of Catmull-Rom smoothing, hour formatting, and day labels.
 */
internal object DesktopGraphUtils {

    // --- Continuous zoom span model -------------------------------------------------------------
    // zoomFactor z in [0,1]: 0 = most zoomed-in (tight, ~±2h), 1 = most zoomed-out (history-leaning
    // 30 days back / 7 days forward). Spans interpolate geometrically because they cover orders of
    // magnitude; forward grows slower than back, so wider views lean into history. History beyond the
    // ~7 days fetched on launch is pulled on demand when the user zooms/pans into it (see
    // DesktopWeatherRepository.ensureHistory).
    const val MIN_BACK_HOURS = 2
    const val MAX_BACK_HOURS = 720   // 30 days of history at full zoom-out
    const val MIN_FORWARD_HOURS = 2
    const val MAX_FORWARD_HOURS = 168 // 7 days forward at full zoom-out
    // Default lands ~12h back (the legacy WIDE span) so opening the popup feels unchanged. Derived
    // from the geometric curve: z = ln(12 / MIN_BACK_HOURS) / ln(MAX_BACK_HOURS / MIN_BACK_HOURS).
    // Must be recomputed whenever MAX_BACK_HOURS changes, since that rescales the whole zoom curve.
    const val DEFAULT_ZOOM_FACTOR = 0.304f

    fun backHoursFor(zoomFactor: Float): Int = geomInterp(MIN_BACK_HOURS, MAX_BACK_HOURS, zoomFactor)

    fun forwardHoursFor(zoomFactor: Float): Int = geomInterp(MIN_FORWARD_HOURS, MAX_FORWARD_HOURS, zoomFactor)

    /** Total visible span (back + forward) for a zoom factor. */
    fun totalSpanHoursFor(zoomFactor: Float): Int = backHoursFor(zoomFactor) + forwardHoursFor(zoomFactor)

    /**
     * How many hours a left/right nav-arrow press shifts the view: half the visible span, so the
     * jump scales with zoom. A fixed step overshoots badly when zoomed in (a 6h jump on a ~4h tight
     * view skips past everything you were looking at); half-a-span keeps ~half the prior window in
     * frame at any zoom. At least 1h so the arrow always moves.
     */
    fun navJumpHours(zoomFactor: Float): Int = (totalSpanHoursFor(zoomFactor) / 2).coerceAtLeast(1)

    private fun geomInterp(min: Int, max: Int, z: Float): Int {
        val zc = z.coerceIn(0f, 1f)
        return (min * (max.toFloat() / min).pow(zc)).roundToInt().coerceIn(min, max)
    }

    /**
     * The continuous [zoomFactor] that best reproduces a discrete [ZoomStage] — the inverse of the
     * back-hours [geomInterp]. Lets the desktop click snap onto a shared stage while the wheel keeps
     * driving the factor continuously. We invert against *back* hours only: one factor can't satisfy
     * both spans for the asymmetric THREE_DAY stage, and history-leaning back-span is what the stages
     * are really about. Yields NARROW→0.0, WIDE→~0.30 (≈[DEFAULT_ZOOM_FACTOR]), THREE_DAY→~0.54.
     */
    fun zoomFactorForStage(stage: ZoomStage): Float =
        (ln(stage.backHours.toFloat() / MIN_BACK_HOURS) / ln(MAX_BACK_HOURS.toFloat() / MIN_BACK_HOURS))
            .coerceIn(0f, 1f)

    /**
     * Once the visible span crosses ~2 days, the bottom strip switches from time-of-day labels
     * ("12a/12p") to one date label per day — bare clock labels can't tell you which day a region
     * belongs to. Tunable; lower = dates kick in sooner.
     */
    const val DATE_LABEL_SPAN_THRESHOLD_HOURS = 48

    /** Clock-hour label cadence (must divide 24, since labels gate on `localHour % interval`). */
    fun labelIntervalFor(totalSpanHours: Int): Int = when {
        totalSpanHours <= 6 -> 1
        totalSpanHours <= 14 -> 2
        totalSpanHours <= 28 -> 4
        totalSpanHours <= 56 -> 6
        totalSpanHours <= 96 -> 12
        else -> 24
    }

    /** Curve smoothing scales up with span: tight views stay crisp, wide views read as a trend. */
    fun smoothIterationsFor(totalSpanHours: Int): Int = when {
        totalSpanHours <= 6 -> 1
        totalSpanHours <= 36 -> 3
        else -> 4
    }

    /** Maps a legacy persisted zoom string to a continuous factor (one-time migration on read). */
    fun zoomFactorFromLegacy(level: String?): Float = if (level == "NARROW") 0f else DEFAULT_ZOOM_FACTOR

    // --- Pan / zoom input math ------------------------------------------------------------------
    /** Mouse-wheel zoom step: one notch (scrollDelta.y ≈ ±1) moves the zoom factor ~0.08 of its range. */
    const val ZOOM_SENSITIVITY = 0.08f

    /**
     * Converts a horizontal drag (pixels) into a signed offset change in hours. Dragging right
     * (positive px) reveals earlier times, so the hourly offset decreases — hence the negative sign.
     */
    fun panDeltaHours(dragAmountPx: Float, widthPx: Float, spanHours: Int): Float =
        if (widthPx <= 0f) 0f else -dragAmountPx * (spanHours.toFloat() / widthPx)

    /**
     * Daily snap-step pan: how many whole days an accumulated horizontal drag should step. Dragging
     * right (positive px) reveals earlier days, so the day offset decreases — hence the negative sign.
     * Truncates toward zero, so a partial column doesn't step until a full [dayWidthPx] is crossed.
     * The caller removes the consumed columns via `accum += result * dayWidthPx`.
     */
    fun panDeltaDays(accumPx: Float, dayWidthPx: Float): Int =
        if (dayWidthPx <= 0f) 0 else -(accumPx / dayWidthPx).toInt()

    /**
     * Sub-hour pixel offset that slides the curve smoothly between whole-hour data steps. The data
     * window sits on `round(dragHours)`; this residual fills the fractional part. Across a half-hour
     * boundary the residual flips sign by exactly the per-hour step the data jumps, so the slide stays
     * continuous. [pixelsPerHour] = graphWidth / spanHours in that graph's mapping units.
     */
    fun dragResidualPx(dragHours: Float, pixelsPerHour: Float): Float =
        -(dragHours - dragHours.roundToInt()) * pixelsPerHour

    /**
     * Catmull-Rom tangent computation for smooth curve interpolation. Delegates to the shared
     * [CurveMath] (which works on plain `(x, y)` pairs) so Android and desktop share the same math;
     * only the `Offset`↔`Pair` mapping happens here.
     */
    fun computeTangents(coords: List<Offset>): List<Offset> =
        CurveMath.computeTangents(coords.map { it.x to it.y }).map { Offset(it.first, it.second) }

    /** Builds a cubic Bezier path through coords using Catmull-Rom tangents. */
    fun buildCurve(coords: List<Offset>): Path = Path().apply {
        if (coords.isEmpty()) return@apply
        moveTo(coords[0].x, coords[0].y)
        if (coords.size > 1) {
            val tangents = computeTangents(coords)
            for (i in 0 until coords.size - 1) {
                val cp1x = coords[i].x + tangents[i].x / 3f
                val cp1y = coords[i].y + tangents[i].y / 3f
                val cp2x = coords[i + 1].x - tangents[i + 1].x / 3f
                val cp2y = coords[i + 1].y - tangents[i + 1].y / 3f
                cubicTo(cp1x, cp1y, cp2x, cp2y, coords[i + 1].x, coords[i + 1].y)
            }
        }
    }

    /** Formats an hour (0-23) to short label: "12a", "1p", etc. */
    fun formatHourLabel(hour: Int): String {
        val hour12 = when (val h = hour % 12) {
            0 -> 12
            else -> h
        }
        val suffix = if (hour < 12) "a" else "p"
        return "$hour12$suffix"
    }

    /** Compact multi-day footer label: weekday + day-of-month, e.g. 2026-06-11 -> "Wed 11". */
    fun formatDateLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()) + " " + date.dayOfMonth

    /**
     * In date mode, one footer label is drawn per visible day, centered under that day. For each
     * local date present in [points] this picks the index whose local time is closest to noon
     * (12:00), so the label sits in the middle of the day's data and partial first/last days still
     * get a (nearest-available) label. Pure, for unit testing.
     */
    fun representativeIndicesByDay(points: List<HourlyForecast>, zone: ZoneId): Set<Int> {
        if (points.isEmpty()) return emptySet()
        return points.indices
            .groupBy { i -> Instant.ofEpochMilli(points[i].dateTime).atZone(zone).toLocalDate() }
            .values
            .map { idxs ->
                idxs.minByOrNull { i ->
                    val ldt = Instant.ofEpochMilli(points[i].dateTime).atZone(zone).toLocalDateTime()
                    abs((ldt.hour * 60 + ldt.minute) - 12 * 60)
                }!!
            }
            .toSet()
    }

    /** Whether the visible span is wide enough that the footer labels its points with dates. */
    fun isDateMode(totalSpanHours: Int): Boolean = totalSpanHours > DATE_LABEL_SPAN_THRESHOLD_HOURS

    /** A point that gets a footer label: its index in the point list and the text to draw. */
    data class FooterLabel(val index: Int, val text: String)

    /**
     * The full footer-label decision for the bottom strip, shared by the temperature, cloud-cover
     * and precipitation graphs: *which* points get a label and *what* text they show. Below the
     * date threshold this is the clock-hour cadence ("12a"/"12p" every [labelIntervalFor] hours);
     * above it, one date per visible day centered on local noon ("Wed 11"). Returned sorted by
     * index. Pure — the draw loops only position and paint what this returns.
     */
    fun footerLabels(points: List<HourlyForecast>, totalSpanHours: Int, zone: ZoneId): List<FooterLabel> {
        if (points.isEmpty()) return emptyList()
        return if (isDateMode(totalSpanHours)) {
            representativeIndicesByDay(points, zone).sorted().map { i ->
                val date = Instant.ofEpochMilli(points[i].dateTime).atZone(zone).toLocalDate()
                FooterLabel(i, formatDateLabel(date))
            }
        } else {
            val interval = labelIntervalFor(totalSpanHours)
            points.indices.mapNotNull { i ->
                val hour = Instant.ofEpochMilli(points[i].dateTime).atZone(zone).toLocalDateTime().hour
                if (hour % interval == 0) FooterLabel(i, formatHourLabel(hour)) else null
            }
        }
    }

    /** Finds the index of the hourly forecast closest to [targetTime]. */
    fun List<HourlyForecast>.indexOfByClosestTime(targetTime: Long): Int {
        var minDiff = Long.MAX_VALUE
        var closestIdx = 0
        forEachIndexed { index, forecast ->
            val diff = abs(forecast.dateTime - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                closestIdx = index
            }
        }
        return closestIdx
    }
}

/**
 * Android-matched temperature-label drop shadow: a solid-black Compose [Shadow] mirroring Android's
 * `setShadowLayer(radius=TEMP_LABEL_SHADOW_RADIUS_DP, dy=TEMP_LABEL_SHADOW_DY_DP, COLOR_SHADOW_SOLID)`.
 * Softer than the old glyph-outline stroke, which read too strong on desktop.
 */
internal fun DrawScope.tempLabelShadow(scale: Float): Shadow = Shadow(
    color = Color(HourlyGraphDefaults.COLOR_SHADOW_SOLID),
    offset = Offset(0f, HourlyGraphDefaults.TEMP_LABEL_SHADOW_DY_DP.dp.toPx() * scale),
    blurRadius = HourlyGraphDefaults.TEMP_LABEL_SHADOW_RADIUS_DP.dp.toPx() * scale,
)

/**
 * Draws [real] with the Android-matched soft drop shadow ([tempLabelShadow]). Re-measures [real]'s
 * own style with the shadow added, so its size / color / font weight are preserved. This is the
 * desktop port of the daily-history high-temp label shadow, using Android's blur rather than a hard
 * glyph outline. Used by both the daily forecast and hourly temperature graphs.
 */
internal fun DrawScope.drawShadowedText(
    measurer: TextMeasurer,
    real: TextLayoutResult,
    topLeft: Offset,
    scale: Float,
) {
    val shadowed = measurer.measure(
        real.layoutInput.text,
        real.layoutInput.style.copy(shadow = tempLabelShadow(scale)),
    )
    drawText(shadowed, topLeft = topLeft)
}

// Thin black outline stroke (fraction of font size) for daily HISTORY temp labels. Larger than
// Android's 0.12 because desktop labels are smaller (14sp), so the same fraction read too faint here.
private const val OUTLINE_STROKE_FRACTION = 0.18f

/**
 * Draws [real] with a thin black glyph OUTLINE (a stroked copy under the fill), keeping the label
 * crisp over a same-colored bar. Used for daily-view history labels only; today/future use plain
 * [drawText] and the hourly graph uses the softer [drawShadowedText] blur.
 */
internal fun DrawScope.drawOutlinedText(
    measurer: TextMeasurer,
    real: TextLayoutResult,
    topLeft: Offset,
) {
    val strokeWidth = real.layoutInput.style.fontSize.toPx() * OUTLINE_STROKE_FRACTION
    val outline = measurer.measure(
        real.layoutInput.text,
        real.layoutInput.style.copy(color = Color.Black, drawStyle = Stroke(width = strokeWidth)),
    )
    drawText(outline, topLeft = topLeft)
    drawText(real, topLeft = topLeft)
}

// --- Shared hourly-graph footer strip + NOW indicator ------------------------------------------
// The Temperature, Precipitation, and CloudCover graphs all draw an identical bottom strip (hour /
// date labels + weather icons) and NOW indicator (dashed line + label). These live here so the
// three renderers can't drift (a past copy-paste left the precip NOW label half-size). Geometry is
// owned by the shared NowIndicatorGeometry; this file only does the Compose drawing.

/**
 * Footer-band metrics: the bottom strip is sized to the actual hour-label height so labels sit
 * flush against the canvas bottom instead of floating with dead space above them.
 */
internal class HourlyFooter(
    val labelFontSize: TextUnit,
    val iconPx: Float,
    val bandHeight: Float,
    val margin: Float,
) {
    /** Bottom y of the plot area: canvas height minus the footer band (plus a small gap above it). */
    fun graphBottom(heightPx: Float, scale: Float): Float = heightPx - (margin + bandHeight + 8f * scale)

    /** Vertical center of the footer band, where labels and icons are anchored. */
    fun bandCenterY(heightPx: Float): Float = heightPx - margin - bandHeight / 2f
}

/** Measures the footer band once (12sp label vs 12dp icon, whichever is taller). */
internal fun DrawScope.hourlyFooter(textMeasurer: TextMeasurer, scale: Float): HourlyFooter {
    val labelFontSize = (12f * scale).sp
    val iconPx = 12.dp.toPx() * scale
    val labelH = textMeasurer.measure("12p", TextStyle(fontSize = labelFontSize)).size.height.toFloat()
    return HourlyFooter(labelFontSize, iconPx, maxOf(labelH, iconPx), 0f * scale)
}

/**
 * The plot bounds and x-axis mapping shared by the cloud-cover and precipitation graphs (the
 * temperature graph computes the same geometry too). Only the per-graph y-mapping (`yAt`, which
 * scales by cloud `topScale` vs precip `yScaleMax`) and the `coords` it produces differ, so those
 * stay at each call site.
 *
 * [xAtTime]/[xAt] map by the actual data span (first..last point) rather than the window, so the
 * rightmost hourly point lands on the right edge and the curve fills the full width. NOW and labels
 * route through [xAtTime] too, so they stay aligned; the window stays the visibility gate at the
 * call site.
 */
internal class HourlyGraphCanvasGeometry(
    val w: Float,
    val h: Float,
    val graphTop: Float,
    val graphBottom: Float,
    val graphHeight: Float,
    val footer: HourlyFooter,
    val xAtTime: (Long) -> Float,
    val xAt: (Int) -> Float,
)

internal fun DrawScope.hourlyGraphCanvasGeometry(
    points: List<HourlyForecast>,
    textMeasurer: TextMeasurer,
    scale: Float,
    dragHours: Float,
): HourlyGraphCanvasGeometry {
    val w = size.width
    val h = size.height
    val graphTop = 38.dp.toPx() * scale
    val footer = hourlyFooter(textMeasurer, scale)
    val graphBottom = footer.graphBottom(h, scale)
    val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

    val dataStart = points.first().dateTime
    val dataEnd = points.last().dateTime
    val dataSpan = (dataEnd - dataStart).coerceAtLeast(1L).toFloat()
    val dragResidualPx = DesktopGraphUtils.dragResidualPx(dragHours, w * 3_600_000f / dataSpan)
    val xAtTime: (Long) -> Float = { t -> (((t - dataStart).toFloat() / dataSpan * w) + dragResidualPx).coerceIn(-w, 2 * w) }
    val xAt: (Int) -> Float = { i -> xAtTime(points[i].dateTime) }

    return HourlyGraphCanvasGeometry(w, h, graphTop, graphBottom, graphHeight, footer, xAtTime, xAt)
}

/**
 * Draws the bottom strip: hour (or per-day date) labels plus the day/night-tinted weather icon at
 * each labeled point. Which points get a label is decided by [DesktopGraphUtils.footerLabels]; this
 * only positions and paints. [painters] is one painter per point (a null entry simply skips the
 * icon). [xAt] maps a point index to its x.
 */
internal fun DrawScope.drawHourlyFooterStrip(
    points: List<HourlyForecast>,
    painters: List<Painter?>,
    totalSpanHours: Int,
    latitude: Double,
    longitude: Double,
    footer: HourlyFooter,
    widthPx: Float,
    heightPx: Float,
    textMeasurer: TextMeasurer,
    scale: Float,
    xAt: (Int) -> Float,
) {
    val labelStyle = TextStyle(fontSize = footer.labelFontSize, color = Color.Gray)
    val measured = DesktopGraphUtils.footerLabels(points, totalSpanHours, ZoneId.systemDefault())
        .map { it to textMeasurer.measure(it.text, labelStyle) }

    // In multi-day date mode the one-label-per-day strip crowds at wide zoom (e.g. a 30-day zoom-out
    // packs ~30 "Wed 11" labels). When the labels plus their weather icons would collide, drop the
    // icons and slant the dates like the forecast-history x-axis so they stay readable. Clock-hour
    // mode (≤2-day spans) keeps its horizontal labels + icons untouched.
    if (DesktopGraphUtils.isDateMode(totalSpanHours) && footerLabelsWouldOverlap(measured, footer, scale, xAt)) {
        // Each label is right-anchored at its tick and rotated like the forecast-history x-axis. That
        // rotation swings the label's lower-left corner ~width*sin(slant) BELOW the pivot, so the
        // pivot baseline must sit that far above the canvas bottom or the text clips off-screen.
        val slantRad = abs(ForecastEvolutionStyle.X_LABEL_SLANT_DEG) * (PI.toFloat() / 180f)
        val maxDownSwing = measured.maxOf { it.second.size.width } * sin(slantRad)
        val baseline = heightPx - maxDownSwing - footer.margin - 2.dp.toPx() * scale
        for ((label, layout) in measured) {
            val x = xAt(label.index)
            rotate(ForecastEvolutionStyle.X_LABEL_SLANT_DEG, pivot = Offset(x, baseline)) {
                drawText(layout, topLeft = Offset(x - layout.size.width, baseline - layout.size.height))
            }
        }
        return
    }

    for ((label, textLayout) in measured) {
        val i = label.index
        val p = points[i]
        val localZdt = Instant.ofEpochMilli(p.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val x = xAt(i)

        val textW = textLayout.size.width.toFloat()
        val textH = textLayout.size.height.toFloat()

        val yOffset = footer.bandCenterY(heightPx)
        val textY = yOffset - textH / 2f

        val isLast = i == points.lastIndex || (x + (textW + 14.dp.toPx() * scale) / 2f > widthPx)

        if (!isLast && painters[i] != null) {
            val iconSize = footer.iconPx
            val gap = 2.dp.toPx() * scale
            val totalW = textW + gap + iconSize

            // Clamp the whole label+icon group to the left edge together so the icon is derived from
            // the same clamped x as the text (otherwise they collide at the edge).
            val startX = (x - totalW / 2f).coerceAtLeast(4f * scale)
            drawText(textLayout, topLeft = Offset(startX, textY))

            val iconLeft = startX + textW + gap
            val iconTop = yOffset - iconSize / 2f

            painters[i]?.let { painter ->
                val sunInfo = SunPositionUtils.getSunInfo(localZdt, latitude, longitude)
                val flags = WeatherIcon.getConditionFlags(p.condition, isNight = sunInfo.isNight).copy(
                    isTwilight = sunInfo.phase == SunPhase.TWILIGHT
                )
                val filter = if (!flags.isRainy && !flags.isMixed) {
                    val tint = when {
                        flags.isNight -> Color(0xFFBBBBBB)
                        flags.isTwilight -> Color(0xFFFFA726)
                        flags.isSunny -> Color(0xFFFFD60A)
                        else -> Color(0xFFBBBBBB)
                    }
                    ColorFilter.tint(tint)
                } else {
                    null
                }
                translate(iconLeft, iconTop) {
                    with(painter) { draw(size = Size(iconSize, iconSize), colorFilter = filter) }
                }
            }
        } else {
            drawText(textLayout, topLeft = Offset(x - textW / 2f, textY))
        }
    }
}

/**
 * Whether the horizontal date-label strip (each label centered on its day, with its weather icon
 * beside it) would collide. Compares each label's full footprint — text + gap + icon — against the
 * spacing between adjacent day positions. True triggers the slanted, icon-less fallback.
 */
private fun DrawScope.footerLabelsWouldOverlap(
    measured: List<Pair<DesktopGraphUtils.FooterLabel, TextLayoutResult>>,
    footer: HourlyFooter,
    scale: Float,
    xAt: (Int) -> Float,
): Boolean {
    if (measured.size < 2) return false
    val gap = 2.dp.toPx() * scale
    val pad = 4.dp.toPx() * scale
    val entries = measured
        .map { (label, layout) -> xAt(label.index) to (layout.size.width + gap + footer.iconPx) }
        .sortedBy { it.first }
    for (k in 0 until entries.size - 1) {
        val (x1, fp1) = entries[k]
        val (x2, fp2) = entries[k + 1]
        if (x2 - x1 < (fp1 + fp2) / 2f + pad) return true
    }
    return false
}

/** Draws the dashed vertical NOW line (drawn early, behind labels). [markerX] = xAtTime(now). */
internal fun DrawScope.drawNowLine(markerX: Float, graphTop: Float, graphHeight: Float, scale: Float) {
    val line = NowIndicatorGeometry.computeNowLine(graphTop, graphHeight)
    drawLine(
        color = Color(HourlyGraphDefaults.COLOR_CURRENT_TIME),
        start = Offset(markerX, line.lineTop),
        end = Offset(markerX, line.lineBottom),
        strokeWidth = HourlyGraphDefaults.CURRENT_TIME_STROKE_DP.dp.toPx() * scale,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(
            HourlyGraphDefaults.CURRENT_TIME_DASH_ON_DP.dp.toPx() * scale,
            HourlyGraphDefaults.CURRENT_TIME_DASH_OFF_DP.dp.toPx() * scale
        ))
    )
}

/**
 * Draws the full-size "NOW" label (drawn last, on top). Below-first, collision-aware placement
 * against [drawnLabels] via NowIndicatorGeometry; appends its box to [drawnLabels] when placed.
 */
internal fun DrawScope.drawNowLabel(
    markerX: Float,
    graphTop: Float,
    graphHeight: Float,
    scale: Float,
    textMeasurer: TextMeasurer,
    drawnLabels: MutableList<Rect>,
) {
    val style = TextStyle(
        fontSize = (14f * HourlyGraphDefaults.NOW_LABEL_TO_TEMP_RATIO * scale).sp,
        color = Color(HourlyGraphDefaults.COLOR_NOW_LABEL),
        shadow = Shadow(
            color = Color(HourlyGraphDefaults.COLOR_SHADOW_LIGHT),
            offset = Offset(0f, 0f),
            blurRadius = HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP.dp.toPx() * scale,
        ),
    )
    val layout = textMeasurer.measure("NOW", style)
    val labelW = layout.size.width.toFloat()
    val labelH = layout.size.height.toFloat()

    // Compose drawText is top-left anchored, so treat the measured box's bottom as the baseline:
    // fontAscent = -height, fontDescent = 0 -> box.top is the top-left y.
    val placement = NowIndicatorGeometry.computeNowLabel(
        nowX = markerX,
        graphTop = graphTop,
        graphHeight = graphHeight,
        labelWidth = labelW,
        fontAscent = -labelH,
        fontDescent = 0f,
        drawnBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) },
        dpToPx = { it.dp.toPx() * scale },
    )
    placement?.let {
        drawText(layout, topLeft = Offset(it.box.left, it.box.top))
        drawnLabels.add(Rect(Offset(it.box.left, it.box.top), Size(labelW, labelH)))
    }
}

/**
 * Draws the left/right edge day-of-week labels ("Mon"/"Tue"), today highlighted. Collision-aware
 * against [occupied] (it tries top / mid / lower-third and appends the chosen box). Shared by all
 * three hourly graphs; the temperature graph uses a larger [dayLabelFontSp] (14) than the cloud/
 * precip graphs (10), so the font size is a parameter.
 */
internal fun DrawScope.drawDayLabels(
    leftDate: LocalDate,
    rightDate: LocalDate,
    textMeasurer: TextMeasurer,
    occupied: MutableList<Rect>,
    scale: Float,
    dayLabelFontSp: Float,
) {
    val today = LocalDate.now()
    val dates = listOf(0f to leftDate, size.width to rightDate)
    dates.forEach { (edgeX, date) ->
        val isToday = date == today
        val color = if (isToday) Color.Yellow.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f)
        val text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        val layout = textMeasurer.measure(text, TextStyle(fontSize = (dayLabelFontSp * scale).sp, color = color))
        val x = edgeX.coerceIn(layout.size.width / 2f, size.width - layout.size.width / 2f)
        val candidates = listOf(8f * scale, size.height * 0.48f, size.height - 48f * scale)
        val y = candidates.firstOrNull { top ->
            val rect = Rect(
                offset = Offset(x - layout.size.width / 2f, top),
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            occupied.none { it.overlaps(rect.inflate(4f * scale)) }
        } ?: candidates.last()
        val rect = Rect(
            offset = Offset(x - layout.size.width / 2f, y),
            size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
        )
        drawText(layout, topLeft = rect.topLeft)
        occupied.add(rect)
    }
}

/**
 * The byte-identical tail block shared by the cloud-cover and precipitation graphs: edge day labels
 * (suppressed in multi-day date mode), the bottom hour/date + icon strip, and the NOW label drawn
 * last (on top), collision-aware against [drawnLabels].
 *
 * The temperature graph intentionally does NOT use this — it interleaves its fetch-dot rings between
 * the footer strip and the NOW label, so combining would reorder its drawing.
 */
internal fun DrawScope.drawDayLabelsFooterAndNow(
    points: List<HourlyForecast>,
    painters: List<Painter?>,
    totalSpanHours: Int,
    latitude: Double,
    longitude: Double,
    footer: HourlyFooter,
    widthPx: Float,
    heightPx: Float,
    textMeasurer: TextMeasurer,
    scale: Float,
    now: Long,
    markerX: Float,
    graphTop: Float,
    graphHeight: Float,
    windowStart: Long,
    windowEnd: Long,
    drawnLabels: MutableList<Rect>,
    xAt: (Int) -> Float,
) {
    // Multi-day spans label the footer with dates instead of times; suppress the redundant interior
    // edge day-labels then (parity with the temperature graph).
    if (!DesktopGraphUtils.isDateMode(totalSpanHours)) {
        drawDayLabels(
            leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
            rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
            textMeasurer = textMeasurer,
            occupied = drawnLabels,
            scale = scale,
            dayLabelFontSp = 10f,
        )
    }
    drawHourlyFooterStrip(points, painters, totalSpanHours, latitude, longitude, footer, widthPx, heightPx, textMeasurer, scale, xAt)
    if (now in windowStart..windowEnd) {
        drawNowLabel(markerX, graphTop, graphHeight, scale, textMeasurer, drawnLabels)
    }
}
