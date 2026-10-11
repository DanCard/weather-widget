package com.weatherwidget.data.local

/**
 * DDL shared by desktop and Room (`SourceViewDayEntity` / `SourceViewTrackingEntity` / `SourceLastViewEntity` declare
 * the same columns; Room MIGRATION_76_77 and MIGRATION_79_80 run these). Backticks and column order match Room's generated schema.
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

    /**
     * One row per source: when the user last switched to it, overwritten on every switch — the last
     * view, not a history ([com.weatherwidget.shared.sourceview.SourceLastView]; Room v80 / desktop v33).
     */
    const val LAST_VIEW_DDL =
        "CREATE TABLE IF NOT EXISTS `source_last_view` (`sourceId` TEXT NOT NULL, " +
            "`lastViewedAtMs` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))"

    /** Upsert one source's last view; both platforms bind (sourceId, lastViewedAtMs). */
    const val LAST_VIEW_UPSERT =
        "INSERT OR REPLACE INTO `source_last_view` (`sourceId`, `lastViewedAtMs`) VALUES (?, ?)"

    /** First write wins: run on every open, it records the day the table first existed. */
    fun trackingStartSql(dayMs: Long): String =
        "INSERT OR IGNORE INTO `source_view_tracking` (`id`, `startedDate`) VALUES (1, $dayMs)"
}
