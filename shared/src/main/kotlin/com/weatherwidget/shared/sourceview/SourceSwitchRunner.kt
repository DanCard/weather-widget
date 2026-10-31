package com.weatherwidget.shared.sourceview

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one rule for a user's switch of the displayed source (API button, daily home button), on
 * Android and desktop alike. Each platform supplies a [Host] for its storage, paint and I/O.
 *
 * The critical path is [Host.persistSelection] then [Host.showFromCache], and nothing else. The view
 * tally, the staleness check / refresh request and the `app_logs` row run afterwards in the caller's
 * deferred scope. A switch used to wait on them: on desktop a `CONFIG_SAVE` app_logs insert sat ahead
 * of the config save and blocked the UI thread for the 5 s SQLite busy timeout (2026-10-29); on Android
 * the staleness probe's Room reads and the forced-refresh enqueue ran before the paint.
 *
 * Every phase is timed into `SOURCE_SWITCH_E2E`: a `phase=critical` line as soon as the new source is
 * painted (so a hung deferred phase cannot hide the latency), and a `phase=done` line, also persisted
 * through [Host.persistLog], once the deferred phase ends.
 * plans/261029-shared-source-switch-runner.md
 */
class SourceSwitchRunner(
    private val host: Host,
    private val platform: String,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class RefreshOutcome {
        /** The source was stale and a refresh was queued. */
        QUEUED,

        /** Fresh enough ([com.weatherwidget.shared.util.SourceToggleRefreshPolicy]); nothing queued. */
        FRESH,

        /** Another process decides (desktop: the daemon's `source_change` launch check). */
        DELEGATED,

        /** Nothing to check against, e.g. no location, so nothing was painted. */
        SKIPPED,

        /** The check or the enqueue failed; the host logged why. */
        FAILED,
    }

    interface Host {
        /**
         * Critical. Makes the new source the displayed one, durably, and returns it; null when there
         * is nothing to switch to (one usable source). Must not write app_logs or anything else that can
         * wait on another process's lock.
         */
        suspend fun persistSelection(): WeatherSource?

        /** Critical. Shows [source] from what is already cached; no network. */
        suspend fun showFromCache(source: WeatherSource)

        /** Deferred. Refreshes [source] if it is stale and reports what it did. */
        suspend fun refreshIfStale(source: WeatherSource): RefreshOutcome

        /** Deferred, non-cancellable: a cancelled interaction still counts the switch it made. */
        suspend fun recordView(source: WeatherSource)

        /** Deferred. Persists one row to app_logs; console logging is the runner's. */
        suspend fun persistLog(tag: String, message: String, level: String)
    }

    /**
     * Runs the critical path in the caller and returns once [Host.showFromCache] is done (or threw).
     * The deferred phase is launched into [deferredScope] and returned; null when nothing was
     * switched. A failure in [Host.persistSelection] is rethrown and nothing else runs; a failure in
     * [Host.showFromCache] still runs the deferred phase (the switch was persisted), then is rethrown.
     */
    suspend fun run(
        from: WeatherSource,
        trigger: SourceViewTrigger,
        deferredScope: CoroutineScope,
    ): Job? {
        val startMs = elapsedMs()
        val to = try {
            host.persistSelection()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$E2E_TAG platform=$platform from=${from.id} trigger=$trigger phase=persist_failed", e)
            throw e
        } ?: run {
            Log.d(TAG, "$E2E_TAG platform=$platform from=${from.id} trigger=$trigger phase=none reason=no_other_source")
            return null
        }
        val persistedMs = elapsedMs()
        val prefix = "platform=$platform from=${from.id} to=${to.id} trigger=$trigger"
        var paintFailure: Throwable? = null
        try {
            host.showFromCache(to)
        } catch (e: Throwable) {
            paintFailure = e
            if (e !is CancellationException) Log.e(TAG, "$E2E_TAG $prefix phase=paint_failed", e)
        }
        val paintedMs = elapsedMs()
        val critical = "persistMs=${persistedMs - startMs} paintMs=${paintedMs - persistedMs} criticalMs=${paintedMs - startMs}"
        if (paintFailure == null) Log.i(TAG, "$E2E_TAG $prefix phase=critical $critical")

        // ATOMIC: the deferred phase starts even if the interaction was cancelled during the paint, so
        // the view is still counted (recordView runs NonCancellable; the later steps stop on cancel).
        val deferred = deferredScope.launch(start = CoroutineStart.ATOMIC) {
            val deferredStartMs = elapsedMs()
            step(prefix, "record_view") { withContext(NonCancellable) { host.recordView(to) } }
            ensureActive()
            val refresh = step(prefix, "refresh") { host.refreshIfStale(to) } ?: RefreshOutcome.FAILED
            ensureActive()
            val line = "$prefix phase=done $critical refresh=$refresh " +
                "deferredMs=${elapsedMs() - deferredStartMs}" +
                if (paintFailure != null) " paint=failed" else ""
            Log.i(TAG, "$E2E_TAG $line")
            step(prefix, "persist_log") { host.persistLog(E2E_TAG, line, "INFO") }
        }
        paintFailure?.let { throw it }
        return deferred
    }

    /** One deferred step: a failure is logged with context and the next step still runs. */
    private suspend fun <T> step(prefix: String, name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$E2E_TAG $prefix phase=deferred_failed step=$name", e)
        null
    }

    companion object {
        private const val TAG = "SourceSwitchRunner"
        const val E2E_TAG = "SOURCE_SWITCH_E2E"

        /** The [E2E_TAG] line for a platform whose data lands after the switch's critical path. */
        fun dataReadyLine(platform: String, fromId: String, toId: String, trigger: SourceViewTrigger, sinceSwitchMs: Long): String =
            "platform=$platform from=$fromId to=$toId trigger=$trigger phase=data_ready sinceSwitchMs=$sinceSwitchMs"
    }
}
