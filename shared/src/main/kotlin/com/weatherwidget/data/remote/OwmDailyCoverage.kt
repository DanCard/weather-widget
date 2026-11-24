package com.weatherwidget.data.remote

/**
 * How much of a later calendar day OpenWeatherMap's free `/2.5/forecast` covers. It is 40 three-hour
 * slots from fetch time, so its last date is cut off at a different hour on every fetch: a morning
 * fetch reaches only that date's pre-dawn hours, and the max of those was drawn as the day's high
 * (Sunday 2026-11-29: 57.5° vs 72–75° from every other source).
 *
 * Classified by the local hour of the date's latest slot. The bounds hold for all three 3-hour UTC
 * alignments a zone can have: the morning low falls in the 05/06/07 slot, the afternoon high in the
 * 15/16/17 slot. The batch's first date (today) is not classified; the today-row rules own it.
 * plans/261124-partial-high-days-shared-row-choice-claude.md
 */
object OwmDailyCoverage {
    enum class Coverage {
        /** Neither the morning low nor the afternoon high: no row, so the climate normal fills the date. */
        OMIT,

        /** The morning low but not the afternoon high: a low-only row (the high is filled at read time). */
        LOW_ONLY,

        /** Both: a complete row. */
        FULL,
    }

    /** First local hour whose slot can hold the morning low. */
    const val LOW_COVERED_FROM_HOUR = 5

    /** First local hour whose slot can hold the afternoon high. */
    const val HIGH_COVERED_FROM_HOUR = 15

    fun classify(maxHour: Int): Coverage = when {
        maxHour < LOW_COVERED_FROM_HOUR -> Coverage.OMIT
        maxHour < HIGH_COVERED_FROM_HOUR -> Coverage.LOW_ONLY
        else -> Coverage.FULL
    }
}
