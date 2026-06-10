package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
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

    private fun geomInterp(min: Int, max: Int, z: Float): Int {
        val zc = z.coerceIn(0f, 1f)
        return (min * (max.toFloat() / min).pow(zc)).roundToInt().coerceIn(min, max)
    }

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
