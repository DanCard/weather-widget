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
import java.time.format.TextStyle as JavaTextStyle

/**
 * Shared utilities for desktop hourly graph renderers (Temperature, Precipitation, CloudCover).
 * Eliminates copy-paste of Catmull-Rom smoothing, hour formatting, and day labels.
 */
internal object DesktopGraphUtils {

    const val WIDE_BACK_HOURS = 12
    const val WIDE_FORWARD_HOURS = 12
    const val WIDE_LABEL_INTERVAL = 4
    const val NARROW_WIDE_LABEL_INTERVAL = 6
    const val NARROW_WIDTH_PX = 420f

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
