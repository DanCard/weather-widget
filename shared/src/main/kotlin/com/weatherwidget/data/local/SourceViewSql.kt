package com.weatherwidget.data.local

/**
 * DDL shared by desktop and Room (`SourceViewDayEntity` / `SourceViewTrackingEntity` declare the same
 * columns; Room MIGRATION_76_77 runs these). Backticks and column order match Room's generated schema.
 * Column names avoid the SQL keywords VIEW and TRIGGER.
 */
object SourceViewSql {
    const val DAYS_DDL =
        "CREATE TABLE IF NOT EXISTS `source_view_days` (`date` INTEGER NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`viewKind` TEXT NOT NULL, `triggerKind` TEXT NOT NULL, `wasPrimary` INTEGER NOT NULL, " +
            "`switches` INTEGER NOT NULL, " +
            "PRIMARY KEY(`date`, `sourceId`, `viewKind`, `triggerKind`, `wasPrimary`))"

    /** One row (id = 1): the day counting started, so days before it are not read as "never switched". */
    const val TRACKING_DDL =
        "CREATE TABLE IF NOT EXISTS `source_view_tracking` (`id` INTEGER NOT NULL, " +
            "`startedDate` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** First write wins: run on every open, it records the day the table first existed. */
    fun trackingStartSql(dayMs: Long): String =
        "INSERT OR IGNORE INTO `source_view_tracking` (`id`, `startedDate`) VALUES (1, $dayMs)"
}
