package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun.Enqueue
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop's [StationNetworkRefreshRun.Host] over SQLite ([DesktopWeatherDao]) and
 * [DesktopWeatherRepository.fetchMetarObservations]; pinned to Android's Room host by
 * `StationNetworkHostContract` (`:shared` testFixtures).
 */
internal class DesktopStationNetworkHost(
    private val weatherDao: DesktopWeatherDao,
    override val lat: Double,
    override val lon: Double,
    private val backgroundSourcesProvider: () -> List<WeatherSource>,
    private val displayedSourceIdsProvider: () -> Set<String>,
    private val fetchMetarFn: suspend (Int) -> List<ObservationReading>,
) : StationNetworkRefreshRun.Host {
    override suspend fun backgroundSources(): List<WeatherSource> = backgroundSourcesProvider()

    override suspend fun displayedSourceIds(): Set<String> = displayedSourceIdsProvider()

    override suspend fun newestStoredTimestampMs(api: String): Long? =
        weatherDao.getNewestObservationTimestampForApi(api, lat, lon)

    override suspend fun fetchMetar(hours: Int): List<ObservationReading> = fetchMetarFn(hours)

    override fun writeHost(): ObservationWriteRun.Host =
        DesktopObservationWriteHost(weatherDao, lat, lon)

    override suspend fun log(tag: String, message: String, level: String) {
        weatherDao.log(tag, message, level)
    }
}

/**
 * The desktop timer for `:shared` [TargetedForecastSyncRun]'s post-sync tail, the counterpart of
 * Android's `weather_widget_post_sync_actuals` unique work: at most one tail waiting and one running,
 * and neither is ever cancelled. [Enqueue.SCHEDULE] arms one if none is waiting or running,
 * [Enqueue.KEEP_PENDING] leaves the waiting one alone, and [Enqueue.APPEND_AFTER_RUNNING] queues one
 * more after the running one.
 */
internal class DesktopPostSyncTail(
    private val scope: CoroutineScope,
    private val runTail: suspend (Set<String>) -> Unit,
) {
    private val lock = Any()
    private var waiting: Job? = null
    private var running = false
    private var next: Pair<Long, Set<String>>? = null

    fun state(): TailState = synchronized(lock) {
        when {
            running -> TailState.RUNNING
            waiting?.isActive == true -> TailState.PENDING
            else -> TailState.NONE
        }
    }

    fun schedule(enqueue: Enqueue, delayMs: Long, sourceIds: Set<String>) = synchronized(lock) {
        when (enqueue) {
            Enqueue.KEEP_PENDING -> Unit
            Enqueue.SCHEDULE -> if (!running && waiting?.isActive != true) waiting = start(delayMs, sourceIds)
            Enqueue.APPEND_AFTER_RUNNING ->
                if (running) {
                    next = delayMs to (next?.second.orEmpty() + sourceIds)
                } else if (waiting?.isActive != true) {
                    waiting = start(delayMs, sourceIds)
                }
        }
    }

    /** Callers hold [lock]. */
    private fun start(delayMs: Long, sourceIds: Set<String>): Job = scope.launch {
        delay(delayMs)
        synchronized(lock) { running = true }
        try {
            runTail(sourceIds)
        } finally {
            synchronized(lock) {
                running = false
                next?.let { (d, ids) ->
                    next = null
                    waiting = start(d, ids)
                }
            }
        }
    }
}

/**
 * Desktop's narrow I/O for `:shared` [TargetedForecastSyncRun]; the rule (order, debounce, yield,
 * station-network refresh, unreachable retry, recompute window, signature comparison, repaint gate,
 * and log lines) is the runner's. [paint] loads what the caller publishes right after the fast path;
 * [publish] pushes a tail's changed actuals to the panel and the window.
 */
internal class DesktopTargetedSyncHost(
    private val repo: DesktopWeatherRepository,
    private val weatherDao: DesktopWeatherDao,
    private val tail: DesktopPostSyncTail,
    private val visibleSources: () -> Set<String>,
    private val backgroundSources: () -> List<WeatherSource> = { visibleSources().map(WeatherSource::fromId) },
    private val displayedSources: () -> Set<String> = { setOf(repo.sourceId) },
    private val userSyncActive: () -> Boolean,
    private val lastInteractionMs: () -> Long? = { null },
    private val publish: suspend () -> Unit = {},
    private val paint: suspend () -> Unit = {},
) : TargetedForecastSyncRun.Host {
    override suspend fun afterFetch() = repo.dailyHistoryAfterFetch()

    override suspend fun paint() = paint.invoke()

    override suspend fun tailState(): TailState = tail.state()

    override suspend fun scheduleTail(enqueue: Enqueue, delayMs: Long, sourceIds: Set<String>) =
        tail.schedule(enqueue, delayMs, sourceIds)

    override suspend fun visibleSourceIds(): Set<String> = visibleSources()

    override suspend fun fillObservationHistory(sourceIds: Set<String>): NwsGapFillRun.Result? =
        repo.fillObservationHistory(sourceIds.map(WeatherSource::fromId), reason = TargetedForecastSyncRun.REPAINT_REASON)

    override fun stationNetworkHost(): StationNetworkRefreshRun.Host = DesktopStationNetworkHost(
        weatherDao = weatherDao,
        lat = repo.latitude,
        lon = repo.longitude,
        backgroundSourcesProvider = backgroundSources,
        displayedSourceIdsProvider = displayedSources,
        fetchMetarFn = { hours -> repo.fetchMetarObservations(hours) },
    )

    override fun blendRecomputeHost(): DailyBlendRecomputeRun.Host = repo.blendRecomputeHost()

    override suspend fun repairsAndUpkeep(nowMs: Long) = repo.repairsAndUpkeep(nowMs)

    override suspend fun dailyHistoryRows(startDateKey: Long, endDateKey: Long): List<DailyHistory> =
        repo.dailyHistoryRows(startDateKey, endDateKey)

    override suspend fun repaint(reason: String) = publish()

    override fun userSyncActive(): Boolean = userSyncActive.invoke()

    override fun lastInteractionMs(): Long? = lastInteractionMs.invoke()

    override suspend fun log(tag: String, message: String, level: String) {
        weatherDao.log(tag, message, level)
    }
}
