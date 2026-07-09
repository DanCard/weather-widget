package com.weatherwidget.shared.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object TempUtils {
    fun fahrenheitToCelsius(f: Float): Float = (f - 32f) / 1.8f
    fun celsiusToFahrenheit(c: Float): Float = c * 1.8f + 32f

    /**
     * Consistently formats a temperature value for display.
     * Shows 1 decimal place if the value is not close to an integer.
     * Uses a consistent 0.01 threshold for "closeness" to ensure high-precision
     * blended values are preserved while official integer forecasts remain clean.
     */
    fun formatTemp(v: Float?, useCelsius: Boolean): String? {
        if (v == null) return null
        val displayVal = if (useCelsius) fahrenheitToCelsius(v) else v
        val rounded = displayVal.roundToInt()
        val result = if (abs(displayVal - rounded) < 0.01f) {
            "$rounded°"
        } else {
            String.format(Locale.getDefault(), "%.1f°", displayVal)
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
