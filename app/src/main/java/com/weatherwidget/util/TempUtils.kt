package com.weatherwidget.util

import com.weatherwidget.shared.util.TempUtils as SharedTempUtils

object TempUtils {
    /**
     * Consistently formats a temperature value for display.
     * Delegates to shared [SharedTempUtils.formatTemp].
     */
    fun formatTemp(v: Float?, useCelsius: Boolean = false): String? {
        val result = SharedTempUtils.formatTemp(v, useCelsius)
        android.util.Log.d("TempUtils", "formatTemp: in=$v useCelsius=$useCelsius out=$result")
        return result
    }

    /**
     * Calculates a simple squared Euclidean distance for sorting nearby locations.
     */
    fun distanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return SharedTempUtils.distanceSq(lat1, lon1, lat2, lon2)
    }
}
