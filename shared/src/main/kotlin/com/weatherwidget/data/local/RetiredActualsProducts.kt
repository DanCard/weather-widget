package com.weatherwidget.data.local

import com.weatherwidget.shared.actuals.TomorrowIoActuals

/**
 * A source that replaced one observation product with another. [currentProductWhere] matches the
 * rows current code writes for [api]; every other row under [api] is retired — no current code
 * writes it, so deleting it only ever removes old data.
 */
data class RetiredActualsProduct(
    val api: String,
    /** SQL predicate (no site clause) for the rows current code still writes under [api]. */
    val currentProductWhere: String,
)

/**
 * Registry of observation-product migrations, shared by Android (Room) and desktop (JDBC). Adding a
 * migration is one entry here; [com.weatherwidget.shared.actuals.RetiredProductCleanup] applies it.
 *
 * Site clauses are [LocationMatch.JDBC_SAME_SITE_WHERE] (positional, bound lat then lon) on both
 * platforms: Android runs these through `@RawQuery`, which takes positional arguments.
 */
object RetiredActualsProducts {
    /**
     * 2026-09-10 (80c977c3): actuals moved to the grid-aligned five-minute history product. The
     * realtime and recent-history endpoints, off-grid five-minute rows and older generic ids are
     * all retired.
     */
    val TOMORROW_IO = RetiredActualsProduct(
        api = "TOMORROW_IO",
        currentProductWhere =
            "stationId = '${TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID}' AND timestamp % 300000 = 0",
    )

    val ALL: List<RetiredActualsProduct> = listOf(TOMORROW_IO)

    fun forApi(api: String): List<RetiredActualsProduct> = ALL.filter { it.api == api }

    /** `observations` rows of the replacement product at the site. */
    fun coverageWhere(product: RetiredActualsProduct): String =
        "api = '${product.api}' AND (${product.currentProductWhere}) AND ${LocationMatch.JDBC_SAME_SITE_WHERE}"

    /** `observations` rows of retired products at the site. */
    fun retiredObservationsWhere(product: RetiredActualsProduct): String =
        "api = '${product.api}' AND NOT (${product.currentProductWhere}) AND ${LocationMatch.JDBC_SAME_SITE_WHERE}"

    /**
     * Computed `daily_history` rows for the source at the site. FORECAST_ONLY_ROW rows (null
     * computed temps) are a display surface, not actuals, and are never touched.
     */
    fun computedDailyRowsWhere(product: RetiredActualsProduct): String =
        "source = '${product.api}' AND computedHighTemp IS NOT NULL AND ${LocationMatch.JDBC_SAME_SITE_WHERE}"
}
