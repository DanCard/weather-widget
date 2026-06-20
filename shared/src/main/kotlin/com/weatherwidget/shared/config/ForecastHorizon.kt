package com.weatherwidget.shared.config

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Single source of truth for how many days of daily forecast we ask a weather API for, shared by
 * Android (`:app`) and the Linux desktop (`:desktop`) so the two can't drift.
 *
 * Two horizons:
 *  - [BASELINE_DAYS]: what every routine/scheduled fetch requests. Includes *today*, so a baseline
 *    of 8 reaches today + 7 days. This was raised from 7 specifically because a 7-day window
 *    (today + 6) drops the day exactly one week out — e.g. on a Saturday, *next* Saturday fell off
 *    the edge and the Open-Meteo bar went missing.
 *  - [MAX_DAYS]: the ceiling Open-Meteo's free `forecast` endpoint allows (`forecast_days` 0..16;
 *    17 is rejected). The on-demand extension fetches this once when the user navigates the daily
 *    view past the baseline edge, which unlocks all further forward navigation.
 *
 * [daysToCover] maps "I need the forecast to reach this target date" to a `forecast_days` value,
 * clamped into `[BASELINE_DAYS, MAX_DAYS]`. Both platforms' on-demand triggers call it so the math
 * is identical; [ForecastHorizonContract] pins the boundary behaviour for tests on both sides.
 */
object ForecastHorizon {
    /** Routine fetch horizon, inclusive of today (8 ⇒ today + 7 days). */
    const val BASELINE_DAYS = 8

    /** Open-Meteo's maximum `forecast_days` (today + 15 days). */
    const val MAX_DAYS = 16

    /**
     * `forecast_days` needed so the daily forecast reaches [target] from [today], inclusive of both
     * ends, clamped to `[BASELINE_DAYS, MAX_DAYS]`. A target at or before today + (BASELINE_DAYS-1)
     * stays at the baseline; anything further requests more, never exceeding the API ceiling.
     */
    fun daysToCover(today: LocalDate, target: LocalDate): Int {
        val span = ChronoUnit.DAYS.between(today, target).toInt() + 1 // inclusive of both endpoints
        return span.coerceIn(BASELINE_DAYS, MAX_DAYS)
    }
}
