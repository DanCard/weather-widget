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
        dailyHistoryDao: DailyHistoryDao,
        appLogDao: AppLogDao,
    ) = mutex.withLock {
        val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        if (prefs.getBoolean(PREF_KEY, false)) return@withLock

        val observationsDeleted = observationDao.deleteLegacyTomorrowIoObservations()
        // computedHigh/Low are non-null columns, so delete and rebuild the debug-only Tomorrow row
        // from the two explicitly accepted observation products.
        val dailyRowsDeleted = dailyHistoryDao.deleteTomorrowIoHistory()
        prefs.edit().putBoolean(PREF_KEY, true).apply()
        appLogDao.log(
            "TMRW_ACTUALS_CLEANUP",
            "legacyObservations=$observationsDeleted dailyRows=$dailyRowsDeleted",
        )
    }
}
