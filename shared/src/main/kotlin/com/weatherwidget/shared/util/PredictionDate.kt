package com.weatherwidget.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day a forecast fetched at `nowMs` counts as made: the **local** calendar date. It sets
 * `forecasts.dateOfPrediction` (1-day-ahead accuracy, forecast-history days-ahead) and which
 * target date is "today" for decimal retention ([ForecastTempRounding]). Shared by Android
 * (`ForecastSnapshotStore`) and desktop (`DesktopWeatherDao.upsertForecasts`).
 *
 * Desktop once used `LocalDate.now(ZoneOffset.UTC)`. From 17:00 PDT that is local tomorrow, so
 * every evening fetch was filed a day late and local today's temperatures were rounded
 * (plans/261005-desktop-forecast-today-is-utc-date-after-5pm.md).
 */
object PredictionDate {
    private const val MS_PER_DAY = 86_400_000L

    fun of(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** [of] in the `forecasts` date encoding: UTC midnight of that calendar date, epoch millis. */
    fun epochMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        of(nowMs, zone).toEpochDay() * MS_PER_DAY
}
