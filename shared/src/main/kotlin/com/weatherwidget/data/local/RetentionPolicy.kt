package com.weatherwidget.data.local

/**
 * How long each table keeps rows — ONE policy for Android and desktop (user's decision, 2026-09-30).
 *
 * `daily_history` is the long record (accuracy stats, "yesterday", history navigation) and keeps
 * 18 months. Every other table keeps at most a month, except the usage records ([USAGE_DAYS]):
 * desktop's `network_usage` (~0.7 MB at 90 days) and `api_usage_stats` (~16 rows a day), which feed
 * the 90-day and calendar-month columns of Settings → Usage stats (user, 2026-10-08).
 *
 * Until 2026-09-30 desktop kept every table 18 months (`DB_RETENTION_DAYS = 547`), `api_usage_stats`,
 * `network_usage` and `current_status` had no limit at all, and Android kept `daily_history` 13 months.
 */
object RetentionPolicy {
    const val DAILY_HISTORY_DAYS = 547L

    /** forecasts, hourly_forecasts, hourly_forecast_history, current_status, station_cache. */
    const val DEFAULT_DAYS = 30L

    /** Raw station readings; the daily actuals they feed live on in daily_history. */
    const val OBSERVATION_DAYS = 10L

    const val APP_LOG_HOURS = 72L

    /**
     * network_usage (desktop) and api_usage_stats: the usage report's 90-day column, and long enough
     * to always hold last calendar month whole (a provider bills per month).
     */
    const val USAGE_DAYS = 90L

    /**
     * source_view_days: how often the user switches sources, read by SourceViewProbability (user,
     * 2026-10-10: "track for 30 days").
     */
    const val SOURCE_VIEW_DAYS = 30L

    private const val DAY_MS = 24L * 3_600_000L

    fun daysAgo(nowMs: Long, days: Long): Long = nowMs - days * DAY_MS

    fun hoursAgo(nowMs: Long, hours: Long): Long = nowMs - hours * 3_600_000L
}
