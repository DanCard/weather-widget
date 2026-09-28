package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.RetiredActualsProduct
import com.weatherwidget.data.local.RetiredActualsProducts

/**
 * The one legacy-observation cleanup for every source, shared by Android and desktop. It only ever
 * deletes old data:
 * - retired observations — rows under a registered source that its current product no longer
 *   writes ([RetiredActualsProducts]), and only once the replacement has rows at the site;
 * - computed daily rows, **only** in the same run that removed retired observations at the site:
 *   those were blended while retired data was present, and removing it changes the day's
 *   observation signature, so the recompute rebuilds them from the current product.
 *
 * Replaces per-source one-off cleanups. Tomorrow.io's deleted its daily rows unconditionally after
 * every fetch, wiping rows just built from the five-minute product (2026-09-28 fold, Warsaw:
 * yesterday deleted 2 s after it was written; 11 `retiredObservations=0 dailyRows=1` deletions in
 * two days), and the recompute's unchanged-observations skip then left the day empty.
 */
object RetiredProductCleanup {
    const val LOG_TAG = "RETIRED_PRODUCT_CLEANUP"

    data class Outcome(val api: String, val coverage: Int, val retiredObservations: Int, val dailyRows: Int) {
        fun logMessage(latitude: Double, longitude: Double): String =
            "api=$api lat=$latitude lon=$longitude coverage=$coverage " +
                "retiredObservations=$retiredObservations dailyRows=$dailyRows"
    }

    /**
     * Retires [product]'s old data at one site. Each lambda receives a WHERE clause from
     * [RetiredActualsProducts] whose site placeholders the platform binds (lat, then lon). Returns
     * null when nothing was deleted — the steady state.
     *
     * Inline so the steps can be `suspend` Room calls on Android and plain JDBC in a transaction on
     * desktop.
     */
    inline fun retireIfCovered(
        product: RetiredActualsProduct,
        countObservations: (where: String) -> Int,
        deleteObservations: (where: String) -> Int,
        deleteDailyRows: (where: String) -> Int,
    ): Outcome? {
        val covered = countObservations(RetiredActualsProducts.coverageWhere(product))
        // Retire nothing until the replacement product exists at this site.
        if (covered == 0) return null
        val retired = deleteObservations(RetiredActualsProducts.retiredObservationsWhere(product))
        // No old observations → no daily row can have been built from them. Leave recent rows alone.
        if (retired == 0) return null
        return Outcome(
            api = product.api,
            coverage = covered,
            retiredObservations = retired,
            dailyRows = deleteDailyRows(RetiredActualsProducts.computedDailyRowsWhere(product)),
        )
    }
}
