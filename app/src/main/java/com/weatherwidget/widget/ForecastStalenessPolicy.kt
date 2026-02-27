package com.weatherwidget.widget

object ForecastStalenessPolicy {
    private const val PRIMARY_API_STALENESS_MS = 60 * 60 * 1000L // 60 min
    private const val SECONDARY_API_STALENESS_MS = 90 * 60 * 1000L // 90 min
    private const val THIRD_API_STALENESS_MS = 120 * 60 * 1000L // 120 min

    fun getStalenessThresholdMs(apiPosition: Int): Long {
        return when (apiPosition) {
            0 -> PRIMARY_API_STALENESS_MS
            1 -> SECONDARY_API_STALENESS_MS
            else -> THIRD_API_STALENESS_MS
        }
    }
}
