package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun.Enqueue
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Desktop's I/O for `:shared` [TargetedForecastSyncRun]; the rule is the runner's. [paint] loads what the
 * caller publishes right after the fast path; [publish] pushes a tail's changed actuals to the panel
 * and the window.
 */
internal class DesktopTargetedSyncHost(
    private val repo: DesktopWeatherRepository,
    private val weatherDao: DesktopWeatherDao,
    private val tail: DesktopPostSyncTail,
    private val visibleSources: () -> Set<String>,
    private val userSyncActive: () -> Boolean,
    private val publish: suspend () -> Unit = {},
    private val onWindowUnreachable: (List<WeatherSource>) -> Unit = {},
    private val paint: suspend () -> Unit = {},
) : TargetedForecastSyncRun.Host {
    override suspend fun afterFetch() = repo.dailyHistoryAfterFetch()

    override suspend fun paint() = paint.invoke()

    override suspend fun tailState(): TailState = tail.state()

    override suspend fun scheduleTail(enqueue: Enqueue, delayMs: Long, sourceIds: Set<String>) =
        tail.schedule(enqueue, delayMs, sourceIds)

    override suspend fun visibleSourceIds(): Set<String> = visibleSources()

    override suspend fun fillObservationHistory(sourceIds: Set<String>) {
        val served = sourceIds.map(WeatherSource::fromId)
        val outcome = repo.refreshObservationWindow(served, reason = TargetedForecastSyncRun.REPAINT_REASON)
        if (outcome == DesktopWeatherRepository.OBS_WINDOW_UNREACHABLE) onWindowUnreachable(served)
    }

    override suspend fun refreshObservationFeeds(sourceIds: Set<String>) {
        // Each feed once, behind its own due gate, as the charging loop runs them.
        repo.refreshObservations(
            visibleSources = sourceIds.map(WeatherSource::fromId),
            reason = TargetedForecastSyncRun.REPAINT_REASON,
        )
    }

    override suspend fun actualsSignature(): Any? = repo.actualsSignature()

    override suspend fun recomputeActuals() {
        repo.recomputeActualsTail()
    }

    override suspend fun repaint(reason: String) = publish()

    override fun userSyncActive(): Boolean = userSyncActive.invoke()

    /** The daemon sees no clicks; a user's fetch in flight is its busy signal ([userSyncActive]). */
    override fun lastInteractionMs(): Long? = null

    override suspend fun log(tag: String, message: String, level: String) {
        weatherDao.log(tag, message, level)
    }
}
