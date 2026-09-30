package com.weatherwidget.data.repository

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.RetentionPolicy
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
    /** Resolved lazily: the repository is constructed in unit tests with a mocked context. */
    private val apiUsageDao: (() -> com.weatherwidget.data.local.ApiUsageDao)? = null,
    /** Daily snapshot prune (see [HistorySnapshotPruner]); gated and throttled by the caller. */
    private val historyPrune: (suspend () -> Unit)? = null,
) {
    suspend fun cleanOldData() {
        val now = System.currentTimeMillis()
        // One policy for both platforms (RetentionPolicy): daily_history 18 months, the rest <= 1 month.
        val defaultCutoff = RetentionPolicy.daysAgo(now, RetentionPolicy.DEFAULT_DAYS)
        val logsCutoffTimestamp = RetentionPolicy.hoursAgo(now, RetentionPolicy.APP_LOG_HOURS)
        forecastDao.deleteOldForecasts(defaultCutoff)
        forecastDao.deleteClimateNormalRows(WeatherSource.GENERIC_GAP.id)
        hourlyForecastDao.deleteOldForecasts(defaultCutoff)
        hourlyForecastHistoryDao.deleteOldHistory(defaultCutoff)
        observationDao.deleteOldObservations(RetentionPolicy.daysAgo(now, RetentionPolicy.OBSERVATION_DAYS))
        dailyHistoryDao.deleteOldExtremes(RetentionPolicy.daysAgo(now, RetentionPolicy.DAILY_HISTORY_DAYS))
        // Best-effort: usage bookkeeping must never fail a sync.
        try {
            apiUsageDao?.invoke()?.deleteOlderThan(defaultCutoff)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeatherRetention", "api_usage_stats retention failed", e)
        }
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
