package com.weatherwidget.shared.config

import java.time.LocalDate

/**
 * Canonical [ForecastHorizon.daysToCover] cases that both platforms rely on. Android and desktop
 * each derive their on-demand "navigated past the edge → fetch this many days" value from the same
 * helper, so this small spec keeps the clamp boundaries from drifting. [BASE] is an arbitrary fixed
 * date; only the offset to the target matters.
 */
object ForecastHorizonContract {
    val BASE: LocalDate = LocalDate.of(2026, 6, 20)

    data class Case(val name: String, val targetOffsetDays: Long, val expectedDays: Int)

    val CASES: List<Case> = listOf(
        Case("today stays at baseline", 0, ForecastHorizon.BASELINE_DAYS),
        Case("within baseline window stays at baseline", 6, ForecastHorizon.BASELINE_DAYS),
        // The original bug: exactly one week out is the last day the baseline must cover (8 ⇒ +7).
        Case("one week out covered by baseline", 7, ForecastHorizon.BASELINE_DAYS),
        Case("just past baseline grows by one", 8, ForecastHorizon.BASELINE_DAYS + 1),
        Case("far future clamps to max", 30, ForecastHorizon.MAX_DAYS),
    )
}
