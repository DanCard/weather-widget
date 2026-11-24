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

    /**
     * The one stored row a date shows for [displaySourceId], among [rows] stored for that date
     * (moved from Android's `DailyViewHandler.buildWeatherByDate`, 2026-11-24):
     * - today … today+2: the display source's row only (null = renders missing);
     * - later: the display source's row when it has a low — complete, or low-only (its high is then the
     *   climate normal's, [PartialForecastDays.fillPartialFutureDays]); otherwise a complete
     *   `GENERIC_GAP` row; otherwise the display source's row as it is; otherwise any gap row.
     * A low-only row used to be swapped for the gap row here, so Android drew a whole climate day
     * where desktop drew the real low (plans/261124-partial-high-days-shared-row-choice-claude.md).
     */
    fun <T> chooseRow(
        rows: List<T>,
        displaySourceId: String,
        date: LocalDate,
        today: LocalDate,
        source: (T) -> String,
        high: (T) -> Float?,
        low: (T) -> Float?,
    ): T? {
        val preferred = rows.firstOrNull { source(it) == displaySourceId }
        if (!allowsClimateNormal(date, today)) return preferred
        if (preferred != null && low(preferred) != null) return preferred
        val gaps = rows.filter { source(it) == WeatherSource.GENERIC_GAP.id }
        return gaps.firstOrNull { high(it) != null && low(it) != null } ?: preferred ?: gaps.firstOrNull()
    }

    fun mayDraw(rowSourceId: String?, displaySourceId: String, date: LocalDate, today: LocalDate): Boolean =
        rowSourceId == displaySourceId ||
            (rowSourceId == WeatherSource.GENERIC_GAP.id && allowsClimateNormal(date, today))
}
