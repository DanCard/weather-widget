package com.weatherwidget.shared.rainmap

import kotlin.math.cos
import kotlin.math.floor

/**
 * The Open-Meteo rain map loop's data: hourly rain amount and chance on a square-ish lat/lon grid
 * centred on the widget's location. See `plans/261020-open-meteo-rain-map-loop.md`.
 *
 * Rows run south → north, columns west → east; values are row-major (`row * cols + col`).
 */
data class RainGrid(
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Hour starts, epoch ms (UTC). */
    val times: LongArray,
    /** `[hour][row * cols + col]`, mm/h. */
    val mm: Array<FloatArray>,
    /** `[hour][row * cols + col]`, percent. */
    val chance: Array<FloatArray>,
    val fetchedAtMs: Long,
) {
    val rows: Int get() = lats.size
    val cols: Int get() = lons.size
    val centerRow: Int get() = rows / 2
    val centerCol: Int get() = cols / 2

    fun values(layer: RainLayer): Array<FloatArray> = if (layer == RainLayer.AMOUNT) mm else chance

    /** Fractional hour index for [nowMs] (0 = first hour), clamped to the series. */
    fun hourPosition(nowMs: Long): Double {
        if (times.isEmpty()) return 0.0
        val pos = (nowMs - times[0]).toDouble() / HOUR_MS
        return pos.coerceIn(0.0, (times.size - 1).toDouble())
    }

    /** [layer] at the grid cell ([row], [col]), linearly blended between hours at [hourPos]. */
    fun valueAt(layer: RainLayer, row: Int, col: Int, hourPos: Double): Float {
        val v = values(layer)
        if (v.isEmpty()) return 0f
        val h0 = floor(hourPos).toInt().coerceIn(0, v.size - 1)
        val h1 = (h0 + 1).coerceAtMost(v.size - 1)
        val t = (hourPos - h0).toFloat().coerceIn(0f, 1f)
        val i = row * cols + col
        return v[h0][i] * (1 - t) + v[h1][i] * t
    }

    // Arrays: structural equality is not used; identity is fine.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}

enum class RainLayer { AMOUNT, CHANCE }

/**
 * Grid geometry. Odd [size] so the centre cell is **exactly** the requested coordinate — the one the
 * widget's own Open-Meteo forecast fetch uses — which makes Open-Meteo snap both to the same model
 * cell and the pin agree with the graph. Longitude span is widened by 1/cos(lat) so cells are about
 * square on the ground.
 */
data class RainGridSpec(
    // 15 points: Open-Meteo counts EVERY location against its free limits (600/min per IP, shared
    // with the widget's own Open-Meteo fetch and, at home, every device). 441-point requests got
    // HTTP 429 on the third within a minute (measured 2026-10-20); 225 leaves room.
    val size: Int = 15,
    val halfSpanLatDeg: Double = 8.0,
    val forecastHours: Int = 48,
) {
    init {
        require(size >= 3 && size % 2 == 1) { "size must be odd and ≥ 3" }
    }

    fun halfSpanLonDeg(centerLat: Double): Double =
        halfSpanLatDeg / cos(Math.toRadians(centerLat.coerceIn(-80.0, 80.0)))

    fun latitudes(centerLat: Double): DoubleArray =
        DoubleArray(size) { i ->
            if (i == size / 2) centerLat else (centerLat - halfSpanLatDeg + 2 * halfSpanLatDeg * i / (size - 1)).coerceIn(-89.9, 89.9)
        }

    fun longitudes(centerLat: Double, centerLon: Double): DoubleArray {
        val half = halfSpanLonDeg(centerLat)
        return DoubleArray(size) { j -> if (j == size / 2) centerLon else centerLon - half + 2 * half * j / (size - 1) }
    }

    companion object {
        /**
         * Start view: ±8° (~1,800 km), ~120 km between points — fronts and systems on the way
         * (user, 2026-10-20: ±4° "still seems too zoomed"). Showers smaller than the spacing can
         * fall between points; that is what [DETAIL] is for.
         */
        val WIDE = RainGridSpec(halfSpanLatDeg = 8.0)

        /** "Zoom in": ±2° (~450 km), ~30 km between points, fetched only when asked for. */
        val DETAIL = RainGridSpec(halfSpanLatDeg = 2.0)

        val DEFAULT = WIDE
    }
}
