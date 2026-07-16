package com.weatherwidget.shared.util

/**
 * Resolves the display values for daily forecast bars (today's solid/forecast lines).
 * Pure-Kotlin, no platform dependencies.
 */
object DailyDayValueResolver {

    /**
     * After this local hour (9am) the day's overnight low is effectively settled, so the today
     * column's low number tracks the observed actual instead of the forecast.
     */
    const val ACTUAL_LOW_CUTOFF_HOUR = 9

    /**
     * After this local hour (5pm = 17:00) the day's high is effectively settled, so the today
     * column's high number tracks the observed actual instead of the forecast.
     */
    const val ACTUAL_HIGH_CUTOFF_HOUR = 17

    data class TodayLineValues(
        /** The "mercury level" high — current temp if available, otherwise observed high. */
        val solidHigh: Float?,
        /** The minimum of stored daily low and current reading. */
        val solidLow: Float?,
        /** The API daily forecast high (dashed line). */
        val forecastHigh: Float?,
        /** The API daily forecast low (dashed line). */
        val forecastLow: Float?,
        /** The faint high-water mark (peak observed so far) — the ghost line reaches up to this. */
        val ghostHigh: Float?,
    )

    /**
     * Resolves today's solid (observed) and dashed (forecast) line values.
     *
     * Correct formula (from Android DailyActualsEstimator):
     * - solidHigh = currentTemp ?: actualHigh — shows real-time temp, falls back to peak
     * - solidLow = min(actualLow, currentTemp) — if current dropped below stored low, reflect that
     * - ghostHigh = actualHigh — the high-water mark the ghost line reaches up to
     *
     * @param actualHigh Observed daily high so far (from DailyHistory)
     * @param actualLow Observed daily low so far (from DailyHistory)
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
            ghostHigh = actualHigh,
        )
    }

    /**
     * The "headline" high used for a column's single high label, matching Android's
     * `effectiveHigh()`.
     *
     * Today = `max(observed, live-forecast, ghost high-water mark)`. This deliberately
     * **excludes** the 24h-prior snapshot — that bar is a comparison overlay, not the
     * headline, so it must not drive the prominent today number (see
     * [selectPriorDaySnapshot][DailySnapshotSelector.selectPriorDaySnapshot]).
     *
     * Once the day's high is settled (local time past [ACTUAL_HIGH_CUTOFF_HOUR]) the number
     * tracks the observed actual (`max(observed, ghost)`) and drops the forecast, so an
     * over-prediction no longer keeps winning the headline. If no actual exists yet we fall
     * back to the forecast-inclusive blend so the column is never blank. The forecast
     * *comparison bar* itself is unaffected — only this number retargets.
     *
     * Other days = the observed high.
     *
     * @param nowHour Local hour-of-day (0–23); null disables the cutoff (legacy behavior).
     */
    fun effectiveHighForLabel(
        isToday: Boolean,
        solidHigh: Float?,
        forecastHigh: Float?,
        ghostHigh: Float?,
        nowHour: Int? = null,
    ): Float? {
        if (!isToday) return solidHigh
        val actualHigh = listOfNotNull(solidHigh, ghostHigh).maxOrNull()
        val highSettled = nowHour != null && nowHour >= ACTUAL_HIGH_CUTOFF_HOUR
        return if (highSettled && actualHigh != null) actualHigh
        else listOfNotNull(solidHigh, forecastHigh, ghostHigh).maxOrNull()
    }

    /**
     * Whether today's headline high is currently tracking the **observed actual** rather than the
     * forecast-inclusive blend — i.e. the exact case where [effectiveHighForLabel] returns
     * `max(observed, ghost)` and drops the forecast.
     *
     * True only for today, once the local time is past [ACTUAL_HIGH_CUTOFF_HOUR] (5pm) AND an actual
     * high exists. Callers use this to recolor the high label to the thermostat (observed) color,
     * signaling the number is now a settled actual instead of a prediction. Mirrors the branch in
     * [effectiveHighForLabel] so the color and the value never disagree.
     *
     * @param nowHour Local hour-of-day (0–23); null disables the cutoff (legacy behavior).
     */
    fun isHighTrackingActual(
        isToday: Boolean,
        solidHigh: Float?,
        ghostHigh: Float?,
        nowHour: Int? = null,
    ): Boolean {
        if (!isToday) return false
        val actualHigh = listOfNotNull(solidHigh, ghostHigh).maxOrNull()
        val highSettled = nowHour != null && nowHour >= ACTUAL_HIGH_CUTOFF_HOUR
        return highSettled && actualHigh != null
    }

    /**
     * The "headline" low used for the today column's single low number. Mirror of
     * [effectiveHighForLabel].
     *
     * Before the cutoff = `min(observed low, forecast low)` (the forecast can still win when
     * the predicted low has not been reached yet). Once the overnight low is settled (local
     * time past [ACTUAL_LOW_CUTOFF_HOUR]) the number tracks the observed actual and drops the
     * forecast; if no actual exists yet it falls back to the forecast-inclusive blend.
     *
     * Only used for today — non-today rows use their observed low directly.
     *
     * @param nowHour Local hour-of-day (0–23); null disables the cutoff (legacy behavior).
     */
    fun effectiveLowForLabel(
        isToday: Boolean,
        solidLow: Float?,
        forecastLow: Float?,
        nowHour: Int? = null,
    ): Float? {
        if (!isToday) return solidLow
        val lowSettled = nowHour != null && nowHour >= ACTUAL_LOW_CUTOFF_HOUR
        return if (lowSettled && solidLow != null) solidLow
        else listOfNotNull(solidLow, forecastLow).minOrNull()
    }

    /**
     * Whether today's headline low is currently tracking the **observed actual** rather than the
     * forecast-inclusive blend — i.e. the exact case where [effectiveLowForLabel] returns the
     * observed low and drops the forecast. Mirror of [isHighTrackingActual].
     *
     * True only for today, once the local time is past [ACTUAL_LOW_CUTOFF_HOUR] (9am) AND an
     * observed low exists. Callers use this to recolor the low label to the thermostat (observed)
     * color, signaling the number is now a settled actual instead of a prediction.
     *
     * @param nowHour Local hour-of-day (0–23); null disables the cutoff (legacy behavior).
     */
    fun isLowTrackingActual(
        isToday: Boolean,
        solidLow: Float?,
        nowHour: Int? = null,
    ): Boolean {
        if (!isToday) return false
        val lowSettled = nowHour != null && nowHour >= ACTUAL_LOW_CUTOFF_HOUR
        return lowSettled && solidLow != null
    }

    /**
     * The vertical anchor for the weather icon + low label: the **lowest drawn bar bottom**
     * for a column, so the icon always sits under the deepest bar regardless of which value the
     * low *number* prints.
     *
     * This is deliberately separate from [effectiveLowForLabel] (the printed value): on today
     * the observed bar may stop higher than the forecast/snapshot comparison bars, and on past
     * days the forecast-overlay bar can dip below the observed bar — in both cases the icon must
     * follow the geometry, not the headline number.
     *
     * @param solidLow Observed/primary bar bottom (today = observed mercury; past = actual; future = forecast).
     * @param forecastLow Forecast comparison bar bottom (dashed overlay), or null when absent.
     * @param snapshotLow 24h-prior snapshot bar bottom (today only), or null when absent.
     */
    fun iconAnchorLow(
        solidLow: Float?,
        forecastLow: Float?,
        snapshotLow: Float?,
    ): Float? = listOfNotNull(solidLow, forecastLow, snapshotLow).minOrNull()
}
