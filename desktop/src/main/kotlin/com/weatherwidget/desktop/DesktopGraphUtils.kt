package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.ZoomStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import java.time.format.TextStyle as JavaTextStyle

/**
 * Shared utilities for desktop hourly graph renderers (Temperature, Precipitation, CloudCover).
 * Eliminates copy-paste of Catmull-Rom smoothing, hour formatting, and day labels.
 */
internal object DesktopGraphUtils {

    // --- Continuous zoom span model -------------------------------------------------------------
    // zoomFactor z in [0,1]: 0 = most zoomed-in (tight, ~±2h), 1 = most zoomed-out (history-leaning
    // 6 days back / 1 day forward). Spans interpolate geometrically because they cover orders of
    // magnitude; forward grows slower than back, so wider views lean into history.
    const val MIN_BACK_HOURS = 2
    const val MAX_BACK_HOURS = 144   // 6 days of history at full zoom-out
    const val MIN_FORWARD_HOURS = 2
    const val MAX_FORWARD_HOURS = 24 // 1 day forward at full zoom-out
    // Default lands ~12h back, close to the legacy WIDE view, so existing users barely notice.
    const val DEFAULT_ZOOM_FACTOR = 0.42f

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
     * are really about. Yields NARROW→0.0, WIDE→~0.42 (≈[DEFAULT_ZOOM_FACTOR]), THREE_DAY→~0.74.
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

    /** Catmull-Rom tangent computation for smooth curve interpolation. */
    fun computeTangents(coords: List<Offset>): List<Offset> {
        if (coords.size < 2) return coords.map { Offset.Zero }
        return coords.indices.map { i ->
            when (i) {
                0 -> Offset(
                    (coords[1].x - coords[0].x) * 0.5f,
                    (coords[1].y - coords[0].y) * 0.5f
                )
                coords.size - 1 -> Offset(
                    (coords[i].x - coords[i - 1].x) * 0.5f,
                    (coords[i].y - coords[i - 1].y) * 0.5f
                )
                else -> {
                    val dxPrev = coords[i].x - coords[i - 1].x
                    val dxNext = coords[i + 1].x - coords[i].x
                    val dx = (dxPrev + dxNext) * 0.5f
                    var dy = (coords[i + 1].y - coords[i - 1].y) * 0.5f

                    val delta1 = coords[i].y - coords[i - 1].y
                    val delta2 = coords[i + 1].y - coords[i].y
                    if (delta1 == 0f || delta2 == 0f || (delta1 > 0 && delta2 < 0) || (delta1 < 0 && delta2 > 0)) {
                        dy = 0f
                    }

                    val maxSafeDx = dxPrev.coerceAtMost(dxNext) * 1.5f
                    if (dx > maxSafeDx && maxSafeDx > 0) {
                        val scale = maxSafeDx / dx
                        Offset(maxSafeDx, dy * scale)
                    } else {
                        Offset(dx, dy)
                    }
                }
            }
        }
    }

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
