package com.weatherwidget.widget

import android.content.Context
import android.os.SystemClock
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.shared.util.TargetedForecastSyncRun

/**
 * The process's one [TargetedForecastSyncRun]: its debounce and yield state must be shared by every
 * worker that asks for or runs a tail. Same clock as [WidgetTimedRepaintGate].
 */
internal object WidgetTargetedSync {
    val run = TargetedForecastSyncRun(SystemClock::elapsedRealtime)
}

/**
 * Android's I/O for `:shared` [TargetedForecastSyncRun]: the rule (order, debounce, yield, repaint gate,
 * log lines) is the runner's. The timer is the `weather_widget_post_sync_actuals` unique work
 * ([WidgetWorkScheduler.enqueuePostSyncActuals]), which never replaces or cancels anything.
 *
 * [paint] is the caller's paint from cache; only the fast path ([TargetedForecastSyncRun.onFetched]) uses it.
 */
internal class AndroidTargetedSyncHost(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val hourlyForecastLoader: HourlyForecastLoader,
    private val dataBundleLoader: WidgetDataBundleLoader,
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

    override suspend fun fillObservationHistory(sourceIds: Set<String>) {
        weatherRepository.backfillNwsObservationsIfNeeded(
            lat, lon, sourceIds.map(WeatherSource::fromId), reason = TargetedForecastSyncRun.REPAINT_REASON,
        )
    }

    override suspend fun refreshObservationFeeds(sourceIds: Set<String>) {
        // Each refresher derives its own tier from the enabled sources; both tiers, as the full sync.
        metarRefresher.refreshIfDue(
            setOf(MetarFetchPolicy.Tier.PRIMARY, MetarFetchPolicy.Tier.NON_PRIMARY),
            lat,
            lon,
            reason = TargetedForecastSyncRun.REPAINT_REASON,
            // Deep pull: daily extremes need the whole day, not the last two hours.
            hours = MetarObservationRefresher.DEEP_HOURS,
        )
    }

    override suspend fun actualsSignature(): Any? = readDisplayedActuals(recompute = false)

    override suspend fun recomputeActuals() {
        readDisplayedActuals(recompute = true)
        weatherRepository.repairFrozenRainChanceIfNeeded(lat, lon)
        weatherRepository.dailyHistoryUpkeepIfDue(lat, lon, isCharging = BatterySnapshotProvider.snapshot(context).isCharging)
    }

    /** The daily actuals the widgets draw, read as a paint reads them; [recompute] also re-reduces the last days first. */
    private suspend fun readDisplayedActuals(recompute: Boolean): DailyActualsBySource {
        val hourly = hourlyForecastLoader.load(lat, lon, hourlyForecastLoader.hourlySourceIds(), caller = "post_sync_actuals")
        return dataBundleLoader.fetchDailyActuals(
            lat = lat,
            lon = lon,
            hourlyForecasts = hourly,
            activeSourceList = hourlyForecastLoader.currentDisplaySourceIds(),
            recompute = recompute,
        )
    }

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
