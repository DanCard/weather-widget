package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One-time removal of legacy generic Tomorrow.io actuals written by older builds. */
internal object TomorrowIoLegacyActualsCleanup {
    private const val PREF_KEY = "tomorrow_actuals_cleanup_v2"
    private val mutex = Mutex()

    suspend fun runIfNeeded(
        context: Context,
        observationDao: ObservationDao,
        appLogDao: AppLogDao,
    ) = mutex.withLock {
        val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        if (prefs.getBoolean(PREF_KEY, false)) return@withLock

        val observationsDeleted = observationDao.deleteLegacyTomorrowIoObservations()
        prefs.edit().putBoolean(PREF_KEY, true).apply()
        appLogDao.log(
            "TMRW_ACTUALS_CLEANUP",
            "legacyObservations=$observationsDeleted dailyRows=0",
        )
    }

    /** Retire conflicting products only after replacement five-minute coverage exists at the site. */
    suspend fun retireConflictingProductsIfCovered(
        latitude: Double,
        longitude: Double,
        observationDao: ObservationDao,
        dailyHistoryDao: DailyHistoryDao,
        appLogDao: AppLogDao,
    ) = mutex.withLock {
        if (observationDao.countTomorrowIoFiveMinuteObservationsAtSite(latitude, longitude) == 0) {
            return@withLock
        }
        val observationsDeleted =
            observationDao.deleteRetiredTomorrowIoProductsAtSite(latitude, longitude)
        val dailyRowsDeleted = dailyHistoryDao.deleteTomorrowIoHistoryAtSite(latitude, longitude)
        if (observationsDeleted == 0 && dailyRowsDeleted == 0) return@withLock
        appLogDao.log(
            "TMRW_5M_CLEANUP",
            "lat=$latitude lon=$longitude coverage=present " +
                "retiredObservations=$observationsDeleted dailyRows=$dailyRowsDeleted",
        )
    }
}
