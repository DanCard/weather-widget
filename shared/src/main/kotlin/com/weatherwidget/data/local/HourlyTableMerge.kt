package com.weatherwidget.data.local

/**
 * The SQL that merges `hourly_forecasts` into `hourly_forecast_history` and leaves one table named
 * `hourly_forecasts`, shared by Room's MIGRATION_83_84 and desktop schema v36. Each platform then
 * creates its own index. `plans/261101-one-hourly-forecast-table.md`.
 *
 * A live row is the newest fetch of its hour, labelled with that fetch's time. OR IGNORE: since
 * 2026-11-01 the same fetch's snapshot already holds it under that label.
 */
object HourlyTableMerge {
    private const val COLUMNS =
        "dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, cloudCover, " +
            "precipAmountMm, fetchedAt, cloudCoverLow, cloudCoverMid, cloudCoverHigh"

    val STATEMENTS: List<String> = listOf(
        "INSERT OR IGNORE INTO hourly_forecast_history ($COLUMNS, timestampToGroupPredictions) " +
            "SELECT $COLUMNS, fetchedAt FROM hourly_forecasts",
        "DROP TABLE hourly_forecasts",
        "ALTER TABLE hourly_forecast_history RENAME TO hourly_forecasts",
        "DROP INDEX IF EXISTS index_hourly_forecast_history_locationLat_locationLon_source_timestampToGroupPredictions",
        "DROP INDEX IF EXISTS idx_hourly_history_lookup",
    )
}
