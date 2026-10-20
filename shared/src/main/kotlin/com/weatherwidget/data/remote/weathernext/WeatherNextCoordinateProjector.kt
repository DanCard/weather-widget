package com.weatherwidget.data.remote.weathernext

import kotlin.math.max
import kotlin.math.min

data class WeatherNextCropBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}

object WeatherNextCoordinateProjector {

    /**
     * Projects geographic (lat, lon) coordinates onto the image pixel coordinates
     * using the exact Matplotlib plot margins defined in WeatherNext cook.py.
     */
    fun projectToPixel(
        lat: Double,
        lon: Double,
        domain: WeatherNextDomain,
        figWidth: Float,
        figHeight: Float,
    ): Pair<Float, Float> {
        val lon360 = (lon + 360.0) % 360.0

        val axLeft = 0.015f * figWidth
        val axRight = 0.98f * figWidth
        val axBottom = 0.03f * figHeight
        val axTop = 0.91f * figHeight

        val lon0 = domain.lon0.toFloat()
        val lon1 = domain.lon1.toFloat()
        val lat0 = domain.lat0.toFloat()
        val lat1 = domain.lat1.toFloat()

        val lonFrac = ((lon360.toFloat() - lon0) / (lon1 - lon0)).coerceIn(0f, 1f)
        val latFrac = ((lat.toFloat() - lat0) / (lat1 - lat0)).coerceIn(0f, 1f)

        val px = axLeft + lonFrac * (axRight - axLeft)
        val py = figHeight - (axBottom + latFrac * (axTop - axBottom))
        return Pair(px, py)
    }

    /**
     * Calculates the crop box around (px, py) for a given zoom level (1.0f = full image).
     */
    fun calculateCropBox(
        px: Float,
        py: Float,
        figWidth: Float,
        figHeight: Float,
        zoom: Float,
    ): WeatherNextCropBox {
        val safeZoom = max(1.0f, zoom)
        val cropW = figWidth / safeZoom
        val cropH = figHeight / safeZoom

        val x1 = max(0f, min(figWidth - cropW, px - cropW / 2f))
        val y1 = max(0f, min(figHeight - cropH, py - cropH / 2f))
        val x2 = x1 + cropW
        val y2 = y1 + cropH

        return WeatherNextCropBox(x1, y1, x2, y2)
    }
}
