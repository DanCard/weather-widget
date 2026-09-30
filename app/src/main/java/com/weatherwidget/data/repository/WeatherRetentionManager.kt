package com.weatherwidget.data.repository

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.WeatherSource

/**
 * Owns forecast, actual, daily-history, and diagnostic-log retention.
 */
internal class WeatherRetentionManager(
    private val forecastDao: ForecastDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    /** Daily snapshot prune (see [HistorySnapshotPruner]); gated and throttled by the caller. */
    private val historyPrune: (suspend () -> Unit)? = null,
) {
    suspend fun cleanOldData() {
        val now = System.currentTimeMillis()
        val oneMonthAgoTimestamp = now - 1000L * 60 * 60 * 24 * 30
        val thirteenMonthsAgoTimestamp = now - 1000L * 60 * 60 * 24 * 395
        val tenDaysAgoTimestamp = now - 1000L * 60 * 60 * 24 * 10
        val logsCutoffTimestamp = now - 1000L * 60 * 60 * 72
        forecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
        forecastDao.deleteClimateNormalRows(WeatherSource.GENERIC_GAP.id)
        hourlyForecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
        hourlyForecastHistoryDao.deleteOldHistory(oneMonthAgoTimestamp)
        observationDao.deleteOldObservations(tenDaysAgoTimestamp)
        dailyHistoryDao.deleteOldExtremes(thirteenMonthsAgoTimestamp)
        appLogDao.deleteOldLogs(logsCutoffTimestamp)
        appLogDao.capUnprotectedToNewest(
            APP_LOG_MAX_ROWS,
            APP_LOG_PROTECTED_TAGS,
        )
        appLogDao.capProtectedToNewest(
            APP_LOG_PROTECTED_MAX_ROWS,
            APP_LOG_PROTECTED_TAGS,
        )
        // Best-effort: a cleanup step must never fail a sync.
        try {
            historyPrune?.invoke()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeatherRetention", "history prune failed", e)
        }
    }

    companion object {
        private const val APP_LOG_MAX_ROWS = 50_000
        private const val APP_LOG_PROTECTED_MAX_ROWS = 25_000

        @VisibleForTesting
        internal val APP_LOG_PROTECTED_TAGS = listOf(
            "WIDGET_PUSH",
            "WIDGET_PAINT",
            "WIDGET_LIFECYCLE",
        )
    }
}
