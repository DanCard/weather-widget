package com.weatherwidget.desktop

import com.weatherwidget.shared.util.DailyRainLabels

/**
 * Header rain-chance font sizing, mirroring Android's
 * `HeaderPrecipCalculator.getPrecipTextSize` + `DailyHeaderResolver` night shrink:
 *
 * - The probability→scale step table ([DailyRainLabels.precipProbabilityScaleFactor]) is shared
 *   with Android, so a trace chance renders tiny and a near-certain chance at full size.
 * - Android sizes the header precip text relative to its header temp base (18dp ==
 *   CURRENT_TEMP_TEXT_SIZE_DP); the desktop analog is its own header temp base of 15sp. Callers
 *   multiply the returned scale by `(HEADER_TEMP_BASE_SP * uiScale).sp`.
 * - The night shrink ([DailyRainLabels.NIGHT_SCALE]) applies only in the daily view when the
 *   next-8h rain is predominantly overnight — matching Android, where the hourly/precip/cloud
 *   views never apply it.
 */
object HeaderPrecipSizing {

    /** Desktop header temp base size in sp — the base the rain chance is scaled from. */
    const val HEADER_TEMP_BASE_SP = 15f

    /**
     * Font scale multiplier for the header rain chance.
     *
     * @param precipProb     Next-8h peak rain probability (0-100).
     * @param isDailyView    True when the daily view is showing (night shrink is daily-only).
     * @param isNightPrecip  True when the next-8h rain is predominantly overnight.
     */
    fun headerPrecipFontScale(precipProb: Int, isDailyView: Boolean, isNightPrecip: Boolean): Float {
        val probScale = DailyRainLabels.precipProbabilityScaleFactor(precipProb)
        val nightScale = if (isDailyView && isNightPrecip) DailyRainLabels.NIGHT_SCALE else 1f
        return probScale * nightScale
    }
}
