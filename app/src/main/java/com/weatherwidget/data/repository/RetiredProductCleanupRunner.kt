package com.weatherwidget.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.RetiredActualsProducts
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.actuals.RetiredProductCleanup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android executor for [RetiredProductCleanup] (desktop: `DesktopWeatherDao.retireProductsIfCovered`).
 * Called after a fetch stores [api]'s observations at a site; a no-op for sources with no
 * registered migration and, once a site's retired rows are gone, for those too.
 *
 * The registry's WHERE clauses select row ids through `@RawQuery`; the deletes are Room-checked
 * `rowid IN (…)` queries, so Room's invalidation tracking still sees them.
 */
internal object RetiredProductCleanupRunner {
    private val mutex = Mutex()

    /** SQLite's default bound-parameter limit is 999; stay under it per delete. */
    private const val DELETE_CHUNK = 500

    suspend fun runFor(
        api: String,
        latitude: Double,
        longitude: Double,
        observationDao: ObservationDao,
        dailyHistoryDao: DailyHistoryDao,
        appLogDao: AppLogDao,
    ) = mutex.withLock {
        val siteArgs = arrayOf<Any>(latitude, longitude)
        fun select(what: String, table: String, where: String) =
            SimpleSQLiteQuery("SELECT $what FROM $table WHERE $where", siteArgs)
        for (product in RetiredActualsProducts.forApi(api)) {
            val outcome = RetiredProductCleanup.retireIfCovered(
                product = product,
                countObservations = { where -> observationDao.countRaw(select("COUNT(*)", "observations", where)) },
                deleteObservations = { where ->
                    observationDao.rowIdsRaw(select("rowid", "observations", where))
                        .chunked(DELETE_CHUNK)
                        .sumOf { observationDao.deleteByRowIds(it) }
                },
                deleteDailyRows = { where ->
                    dailyHistoryDao.rowIdsRaw(select("rowid", "daily_history", where))
                        .chunked(DELETE_CHUNK)
                        .sumOf { dailyHistoryDao.deleteByRowIds(it) }
                },
            ) ?: continue
            appLogDao.log(RetiredProductCleanup.LOG_TAG, outcome.logMessage(latitude, longitude))
        }
    }
}
