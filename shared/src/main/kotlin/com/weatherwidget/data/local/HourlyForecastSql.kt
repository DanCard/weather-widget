package com.weatherwidget.data.local

/**
 * The column list of the "newest fetch's row per hour" reads of `hourly_forecasts`, on both platforms
 * (Room `HourlyForecastDao`, desktop `DesktopWeatherDao`). Used where those queries had `SELECT *`.
 *
 * Identical to the table's columns except `precipAmountMm`: when the newest row has none, it is the
 * newest non-null amount stored for the same hour, source and site. A provider whose amounts come from
 * a forward-looking grid (NWS QPF) returns none for an hour that has elapsed, so every elapsed-hour
 * backfill before 2026-11-13 stored a null over the amount an earlier fetch had recorded and a day's
 * 8am–8pm total went blank for every past NWS day (plans/261113-nws-day-amount-null-elapsed-hour.md).
 * The older rows are still stored; this read recovers them. `COALESCE` evaluates the subquery only
 * for a null amount, and it rides `idx_hourly_site`.
 *
 * Add a column to `hourly_forecasts` → add it here too ([HourlyForecastSqlColumnsTest] fails if not).
 */
object HourlyForecastSql {
    const val NEWEST_ROW_COLUMNS =
        "dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, " +
            "precipProbability, cloudCover, " +
            "COALESCE(precipAmountMm, (SELECT o.precipAmountMm FROM hourly_forecasts o " +
            "WHERE o.dateTime = hourly_forecasts.dateTime AND o.source = hourly_forecasts.source " +
            "AND o.locationLat = hourly_forecasts.locationLat AND o.locationLon = hourly_forecasts.locationLon " +
            "AND o.precipAmountMm IS NOT NULL ORDER BY o.fetchedAt DESC LIMIT 1)) AS precipAmountMm, " +
            "fetchedAt, cloudCoverLow, cloudCoverMid, cloudCoverHigh, visibilityMi, issuedAt"
}
