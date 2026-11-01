package com.weatherwidget.data.local

/**
 * How long each table keeps rows — ONE policy for Android and desktop (user's decision, 2026-09-30).
 *
 * `daily_history` and `hourly_actuals` are the long record (accuracy stats, "yesterday", history
 * navigation) and keep 18 months. Every other table keeps at most a month, except the usage records
 * ([USAGE_DAYS]):
 * desktop's `network_usage` (~0.7 MB at 90 days) and `api_usage_stats` (~16 rows a day), which feed
 * the 90-day and calendar-month columns of Settings → Usage stats (user, 2026-10-08).
 *
 * Until 2026-09-30 desktop kept every table 18 months (`DB_RETENTION_DAYS = 547`), `api_usage_stats`,
 * `network_usage` and `current_status` had no limit at all, and Android kept `daily_history` 13 months.
 */
object RetentionPolicy {
    const val DAILY_HISTORY_DAYS = 547L

    /** hourly_actuals: the blended hourly record, as long as the daily one. */
    const val HOURLY_ACTUALS_DAYS = DAILY_HISTORY_DAYS

    /**
     * An hour is archived to hourly_actuals at this age. Past
     * `DailyBlendRecomputeRun.MAX_DAYS_BACK` (9) plus its 24 h of context and a partial calendar day,
     * so no recompute still needs the hour's raw rows, and late station reports have landed.
     */
    const val HOURLY_ACTUALS_ARCHIVE_DAYS = 11L

    /** Raw observations outlive the archive by this much: every blend reads 24 h either side. */
    const val OBSERVATION_ARCHIVE_MARGIN_DAYS = 2L

    /** hourly_forecasts keeps every reader's snapshots this long; older hours lose their newest-snapshot rows. */
    const val HOURLY_FORECAST_FULL_DAYS = 5L

    /**
     * Forecast hours are deleted at this age. The 30-day pan, rain accuracy (30 calendar days) and
     * the prior-day cloud curve (31 days) all reach 31 days of age.
     */
    const val HOURLY_FORECAST_DAYS = 32L

    /** forecasts, current_status, station_cache. */
    const val DEFAULT_DAYS = 30L

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
