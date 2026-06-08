package com.weatherwidget.shared.util

/**
 * Resolves the display values for daily forecast bars (today's solid/forecast lines).
 * Pure-Kotlin, no platform dependencies.
 */
object DailyDayValueResolver {

    data class TodayLineValues(
        /** The "mercury level" high — current temp if available, otherwise observed high. */
        val solidHigh: Float?,
        /** The minimum of stored daily low and current reading. */
        val solidLow: Float?,
        /** The API daily forecast high (dashed line). */
        val forecastHigh: Float?,
        /** The API daily forecast low (dashed line). */
        val forecastLow: Float?,
    )

    /**
     * Resolves today's solid (observed) and dashed (forecast) line values.
     *
     * Correct formula (from Android DailyActualsEstimator):
     * - solidHigh = currentTemp ?: actualHigh — shows real-time temp, falls back to peak
     * - solidLow = min(actualLow, currentTemp) — if current dropped below stored low, reflect that
     *
     * @param actualHigh Observed daily high so far (from DailyExtreme)
     * @param actualLow Observed daily low so far (from DailyExtreme)
     * @param forecastHigh API daily forecast high
     * @param forecastLow API daily forecast low
     * @param currentTemp Most recently observed current temperature
     */
    fun resolveTodayLineValues(
        actualHigh: Float?,
        actualLow: Float?,
        forecastHigh: Float?,
        forecastLow: Float?,
        currentTemp: Float?,
    ): TodayLineValues {
        val solidHigh = currentTemp ?: actualHigh
        val solidLow = listOfNotNull(actualLow, currentTemp).minOrNull()
        return TodayLineValues(
            solidHigh = solidHigh,
            solidLow = solidLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
        )
    }
}
