package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate

/**
 * What a daily column does with a forecast row that has only a high or only a low. NWS produces
 * both shapes routinely: it stops reporting today's low in the evening, and its last day is often
 * low-only. Shared by Android (`DailyTodayResolver`, `DailyActualsEstimator`,
 * `DailyFutureDayResolver`) and desktop (`DesktopWeatherDao`, `DesktopDailyForecastModel`,
 * `DesktopWeatherRepository`). Desktop used to store such rows with low = high and repair any flat
 * day at read time instead. See plans/261002-share-nws-daily-pipeline-and-partial-days.md.
 */
object PartialForecastDays {

    /**
     * How far behind the newest stored row a complete row may be and still stand in for today's
     * partial one. NWS drops today's low in the evening, so that morning's complete row (hours old)
     * is the replacement meant here. Without a bound a week-old row won: a site first fetched on
     * Oct 2 drew that fetch's 90/74 for Oct 9 over the 69.5 fetched that day (2026-10-09).
     */
    const val COMPLETE_REPLACEMENT_MAX_AGE_MS = 24L * 3_600_000L

    /**
     * Today's partial row is replaced by the newest stored row for the day that has both values,
     * when that row is no more than [COMPLETE_REPLACEMENT_MAX_AGE_MS] older than the newest stored row.
     */
    fun <T> completeReplacement(
        candidates: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        val newestMs = candidates.maxOfOrNull(fetchedAt) ?: return null
        return candidates
            .filter { high(it) != null && low(it) != null }
            .filter { newestMs - fetchedAt(it) <= COMPLETE_REPLACEMENT_MAX_AGE_MS }
            .maxByOrNull(fetchedAt)
    }

    /**
     * The row that stands for today's forecast, shared by Android (`DailyViewLogic`) and desktop
     * (`DesktopWeatherDao.getDailyForecasts`). [batchRow] is today's row in the newest fetch, or
     * null when that fetch has none. Silurian's daily output starts at the current UTC date, so
     * its evening batches skip local today. [storedRows] are the display source's stored rows for
     * today at this site. In order:
     * 1. [batchRow] when it has both values;
     * 2. the newest stored row with both (NWS stops reporting today's low in the evening), when
     *    fetched within [COMPLETE_REPLACEMENT_MAX_AGE_MS] of the newest stored row;
     * 3. the newest one-sided row, [batchRow] first (the column fills the other side from hourly);
     * 4. null, so the climate normal stays the last resort.
     * Desktop once read only the newest batch and drew the normal for Silurian's today
     * (plans/261004-desktop-today-column-climate-normal-when-batch-lacks-today.md).
     */
    fun <T> todayRow(
        batchRow: T?,
        storedRows: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        if (batchRow != null && high(batchRow) != null && low(batchRow) != null) return batchRow
        return completeReplacement(storedRows, high, low, fetchedAt)
            ?: batchRow
            ?: storedRows.filter { high(it) != null || low(it) != null }.maxByOrNull(fetchedAt)
    }

    /** Today's forecast range: the daily value, else the day's hourly max / min. */
    fun todayForecastRange(
        dailyHigh: Float?,
        dailyLow: Float?,
        todayHourlyTemps: List<Float>,
    ): Pair<Float?, Float?> =
        (dailyHigh ?: todayHourlyTemps.maxOrNull()) to (dailyLow ?: todayHourlyTemps.minOrNull())

    /**
     * NWS's last forecast day reporting only an overnight low. It is real data, not a gap, so it is
     * drawn as-is rather than replaced by climate normals.
     */
    fun isTerminalLowOnlyNwsFutureDay(
        source: String?,
        high: Float?,
        low: Float?,
        date: LocalDate,
        today: LocalDate,
        lastNwsFutureDate: LocalDate?,
    ): Boolean =
        source == WeatherSource.NWS.id &&
            date.isAfter(today) &&
            high == null &&
            low != null &&
            date == lastNwsFutureDate

    data class FutureValues(val high: Float?, val low: Float?, val isClimateOverlay: Boolean)

    /** A partial future day takes both values from the climate normal for its date, when one exists. */
    fun futureFromNormals(
        high: Float?,
        low: Float?,
        isTerminalLowOnlyNws: Boolean,
        normal: Pair<Float, Float>?,
    ): FutureValues =
        if (!isTerminalLowOnlyNws && (high == null || low == null) && normal != null) {
            FutureValues(normal.first, normal.second, isClimateOverlay = true)
        } else {
            FutureValues(high, low, isClimateOverlay = false)
        }
}
