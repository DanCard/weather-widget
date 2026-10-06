package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate

/**
 * Which stored daily rows may stand in a daily column drawn for a given display source.
 *
 * Only the display source's own rows, plus climate-normal filler (`GENERIC_GAP`) for long-term
 * future days the providers do not cover (after today+2). Never another provider's forecast: a column
 * labelled "Google" once drew NWS's numbers because a snapshot fallback accepted any real source
 * (plans/261006-daily-future-column-cross-source-snapshot-fallback.md). A date with no acceptable
 * row renders missing until its fetch lands.
 */
object DailyColumnSource {
    /** Climate normals may fill a date only after this many days ahead of today. */
    const val CLIMATE_NORMAL_AFTER_DAYS = 2L

    fun allowsClimateNormal(date: LocalDate, today: LocalDate): Boolean =
        date.isAfter(today.plusDays(CLIMATE_NORMAL_AFTER_DAYS))

    fun mayDraw(rowSourceId: String?, displaySourceId: String, date: LocalDate, today: LocalDate): Boolean =
        rowSourceId == displaySourceId ||
            (rowSourceId == WeatherSource.GENERIC_GAP.id && allowsClimateNormal(date, today))
}
