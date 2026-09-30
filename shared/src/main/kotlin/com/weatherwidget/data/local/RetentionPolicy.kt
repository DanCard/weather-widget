package com.weatherwidget.data.local

/**
 * How long each table keeps rows — ONE policy for Android and desktop (user's decision, 2026-09-30).
 *
 * `daily_history` is the long record (accuracy stats, "yesterday", history navigation) and keeps
 * 18 months. Every other table keeps at most a month. The one exception is desktop's
 * `network_usage` (~0.7 MB at 90 days), which feeds the 90-day column of the data-usage report.
 *
 * Until 2026-09-30 desktop kept every table 18 months (`DB_RETENTION_DAYS = 547`), `api_usage_stats`,
 * `network_usage` and `current_status` had no limit at all, and Android kept `daily_history` 13 months.
 */
object RetentionPolicy {
    const val DAILY_HISTORY_DAYS = 547L

    /** forecasts, hourly_forecasts, hourly_forecast_history, api_usage_stats, current_status, station_cache. */
    const val DEFAULT_DAYS = 30L

    /** Raw station readings; the daily actuals they feed live on in daily_history. */
    const val OBSERVATION_DAYS = 10L

    const val APP_LOG_HOURS = 72L

    /** Desktop data-usage report shows a 90-day column. */
    const val NETWORK_USAGE_DAYS = 90L

    private const val DAY_MS = 24L * 3_600_000L

    fun daysAgo(nowMs: Long, days: Long): Long = nowMs - days * DAY_MS

    fun hoursAgo(nowMs: Long, hours: Long): Long = nowMs - hours * 3_600_000L
}
