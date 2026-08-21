package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One-time removal of Open-Meteo Forecast API model rows previously stored as actuals. */
internal object OpenMeteoLegacyActualsCleanup {
    private const val PREF_KEY = "open_meteo_model_actuals_cleanup_v1"
    private val mutex = Mutex()

    suspend fun runIfNeeded(
        context: Context,
        observationDao: ObservationDao,
        dailyHistoryDao: DailyHistoryDao,
        appLogDao: AppLogDao,
    ) = mutex.withLock {
        val prefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        if (prefs.getBoolean(PREF_KEY, false)) return@withLock

        val observationsDeleted = observationDao.deleteOpenMeteoModelObservations()
        val dailyRowsDeleted = dailyHistoryDao.deleteOpenMeteoHistory()
        prefs.edit().putBoolean(PREF_KEY, true).apply()
        appLogDao.log(
            "METEO_ACTUALS_CLEANUP",
            "modelObservations=$observationsDeleted dailyRows=$dailyRowsDeleted",
        )
    }
}
