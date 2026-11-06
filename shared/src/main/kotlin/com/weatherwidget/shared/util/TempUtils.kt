package com.weatherwidget.shared.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object TempUtils {
    fun fahrenheitToCelsius(f: Float): Float = (f - 32f) / 1.8f
    fun celsiusToFahrenheit(c: Float): Float = c * 1.8f + 32f

    fun display(f: Float, useCelsius: Boolean): Float = if (useCelsius) fahrenheitToCelsius(f) else f
    fun display(f: Double, useCelsius: Boolean): Double = if (useCelsius) (f - 32.0) / 1.8 else f

    fun displayDelta(deltaF: Float, useCelsius: Boolean): Float = if (useCelsius) deltaF / 1.8f else deltaF
    fun displayDelta(deltaF: Double, useCelsius: Boolean): Double = if (useCelsius) deltaF / 1.8 else deltaF


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
     * A daily column's temperature label, both platforms. [wholeDegrees] is the column's
     * `DailyColumn.printsWholeDegrees` (a future provider forecast); otherwise as [formatTemp].
     * Forecasts are stored at the provider's precision (plans/261105-store-full-precision-forecast-temps.md);
     * this is where a column rounds. History of Forecasts never rounds (it calls [formatTemp]).
     */
    fun formatDayTemp(v: Float?, useCelsius: Boolean, wholeDegrees: Boolean): String? {
        if (v == null) return null
        if (!wholeDegrees) return formatTemp(v, useCelsius)
        return "${display(v, useCelsius).roundToInt()}°"
    }

    /**
     * A signed temperature difference (°F in), as [formatTemp] prints a value: "+2°", "-1.4°", "0°".
     * The forecast-accuracy rows on both platforms use it; errors are kept at full precision.
     */
    fun formatSignedDelta(deltaF: Float, useCelsius: Boolean): String {
        val v = displayDelta(deltaF, useCelsius)
        val rounded = v.roundToInt()
        val body = if (abs(v - rounded) < 0.01f) {
            "${abs(rounded)}"
        } else {
            String.format(Locale.getDefault(), "%.1f", abs(v))
        }
        return when {
            body.trimStart('0', '.', ',').isEmpty() -> "0°"
            v > 0f -> "+$body°"
            else -> "-$body°"
        }
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
