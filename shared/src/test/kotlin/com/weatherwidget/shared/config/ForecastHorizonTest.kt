package com.weatherwidget.shared.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit coverage for [ForecastHorizon.daysToCover] via the shared [ForecastHorizonContract]
 * cases — the same boundaries Android and desktop both clamp to when deciding how far to extend an
 * on-demand forecast fetch. Guards the baseline floor (the original "next Saturday dropped off a
 * 7-day window" bug) and the Open-Meteo 16-day ceiling.
 */
class ForecastHorizonTest {

    @Test
    fun `daysToCover honours the shared contract boundaries`() {
        for (case in ForecastHorizonContract.CASES) {
            val target = ForecastHorizonContract.BASE.plusDays(case.targetOffsetDays)
            assertEquals(
                case.name,
                case.expectedDays,
                ForecastHorizon.daysToCover(ForecastHorizonContract.BASE, target),
            )
        }
    }

    @Test
    fun `extensionTarget honours the shared contract boundaries`() {
        for (case in ForecastHorizonContract.EXTENSION_CASES) {
            val rightmost = ForecastHorizonContract.BASE.plusDays(case.rightmostOffsetDays)
            val coverageMax = case.coverageOffsetDays?.let { ForecastHorizonContract.BASE.plusDays(it) }
            assertEquals(
                case.name,
                case.expected,
                ForecastHorizon.extensionTarget(ForecastHorizonContract.BASE, rightmost, coverageMax),
            )
        }
    }

    @Test
    fun `a target before today never drops below baseline`() {
        val yesterday = ForecastHorizonContract.BASE.minusDays(1)
        assertEquals(
            ForecastHorizon.BASELINE_DAYS,
            ForecastHorizon.daysToCover(ForecastHorizonContract.BASE, yesterday),
        )
    }
}
