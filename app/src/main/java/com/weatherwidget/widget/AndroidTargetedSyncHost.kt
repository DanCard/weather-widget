package com.weatherwidget.widget

import android.content.Context
import android.os.SystemClock
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun

/**
 * The process's one [TargetedForecastSyncRun]: its debounce and yield state must be shared by every
 * worker that asks for or runs a tail. Same clock as [WidgetTimedRepaintGate].
 */
internal object WidgetTargetedSync {
    val run = TargetedForecastSyncRun(clock = SystemClock::elapsedRealtime)
}

/**
 * Android's narrow I/O for `:shared` [TargetedForecastSyncRun]: the rule (order, debounce, yield,
 * station-network refresh, unreachable retry, recompute window, signature comparison, repaint gate,
 * and log lines) is the runner's. The timer is the `weather_widget_post_sync_actuals` unique work
 * ([WidgetWorkScheduler.enqueuePostSyncActuals]), which never replaces or cancels anything.
 *
 * [paint] is the caller's paint from cache; only the fast path ([TargetedForecastSyncRun.onFetched]) uses it.
 */
internal class AndroidTargetedSyncHost(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val metarRefresher: MetarObservationRefresher,
    private val location: Pair<Double, Double>,
    private val paint: suspend () -> Unit = {},
) : TargetedForecastSyncRun.Host {
    private val lat get() = location.first
    private val lon get() = location.second

    override suspend fun afterFetch() {
        weatherRepository.dailyHistoryAfterFetch(lat, lon)
    }

    override suspend fun paint() = paint.invoke()

    override suspend fun tailState(): TargetedForecastSyncRun.TailState =
        WidgetWorkScheduler.postSyncActualsState(context)

    override suspend fun scheduleTail(enqueue: TargetedForecastSyncRun.Enqueue, delayMs: Long, sourceIds: Set<String>) {
        WidgetWorkScheduler.enqueuePostSyncActuals(context, enqueue, delayMs, sourceIds)
    }

    override suspend fun visibleSourceIds(): Set<String> =
        widgetStateManager.getVisibleSourcesOrder().map { it.id }.toSet()

    override suspend fun fillObservationHistory(sourceIds: Set<String>): NwsGapFillRun.Result? =
        weatherRepository.backfillNwsObservationsIfNeeded(
            lat, lon, sourceIds.map(WeatherSource::fromId), reason = TargetedForecastSyncRun.REPAINT_REASON,
        )

    override fun stationNetworkHost(): StationNetworkRefreshRun.Host =
        metarRefresher.hostFor(lat, lon)

    override fun blendRecomputeHost(): DailyBlendRecomputeRun.Host =
        weatherRepository.blendRecomputeHost(lat, lon)

    override suspend fun repairsAndUpkeep(nowMs: Long) {
        weatherRepository.repairFrozenRainChanceIfNeeded(lat, lon)
        weatherRepository.dailyHistoryUpkeepIfDue(lat, lon, isCharging = BatterySnapshotProvider.snapshot(context).isCharging)
    }

    override suspend fun dailyHistoryRows(startDateKey: Long, endDateKey: Long): List<DailyHistory> =
        weatherRepository.getDailyHistoryInRange(startDateKey, endDateKey, lat, lon)

    override suspend fun repaint(reason: String) {
        WidgetWorkScheduler.enqueueUiRepaint(context, reason = reason)
    }

    override fun userSyncActive(): Boolean = WidgetBackfillDeferral.policy.userSyncActive()

    override fun lastInteractionMs(): Long? {
        val (active, last) = WidgetTimedRepaintGate.gate.interactionSnapshot()
        return if (active > 0) SystemClock.elapsedRealtime() else last
    }

    override suspend fun log(tag: String, message: String, level: String) {
        appLogDao.log(tag, message, level)
    }
}
