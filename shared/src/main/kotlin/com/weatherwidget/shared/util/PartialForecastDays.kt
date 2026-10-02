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

    /** Today's partial row is replaced by the newest stored row for the day that has both values. */
    fun <T> completeReplacement(
        candidates: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? = candidates.filter { high(it) != null && low(it) != null }.maxByOrNull(fetchedAt)

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
