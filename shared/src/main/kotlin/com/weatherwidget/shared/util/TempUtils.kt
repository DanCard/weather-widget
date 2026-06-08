package com.weatherwidget.shared.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object TempUtils {
    /**
     * Consistently formats a temperature value for display.
     * Shows 1 decimal place if the value is not close to an integer.
     * Uses a consistent 0.01 threshold for "closeness" to ensure high-precision
     * blended values are preserved while official integer forecasts remain clean.
     */
    fun formatTemp(v: Float?): String? {
        if (v == null) return null
        val rounded = v.roundToInt()
        val result = if (abs(v - rounded) < 0.01f) {
            "$rounded°"
        } else {
            String.format(Locale.getDefault(), "%.1f°", v)
        }
        return result
    }

    /**
     * Calculates a simple squared Euclidean distance for sorting nearby locations.
     */
    fun distanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat1 - lat2
        val dLon = lon1 - lon2
        return dLat * dLat + dLon * dLon
    }
}
