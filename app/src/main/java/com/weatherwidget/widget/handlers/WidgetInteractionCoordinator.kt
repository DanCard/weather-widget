package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns interaction serialization, outcome breadcrumbs, isolated batch execution, and resize
 * coalescing. Rendering and forecast queries intentionally live outside this concurrency boundary.
 */
internal object WidgetInteractionCoordinator {
    private const val TAG = "WidgetInteractionCoord"
    private const val RESIZE_DEBOUNCE_MS = 250L

    data class Metadata(val value: String = "")

    private val interactionMutexes = ConcurrentHashMap<Int, Mutex>()
    private val resizeRequestSequence = AtomicLong(0L)
    private val latestResizeRequest = ConcurrentHashMap<Int, Long>()

    suspend fun <T> withWidgetLock(
        appWidgetId: Int,
        block: suspend () -> T,
    ): T = interactionMutexes.computeIfAbsent(appWidgetId) { Mutex() }.withLock { block() }

    /**
     * Captures [metadata] after taking the widget lock, then performs [block] under that same lock.
     * This makes state-transition breadcrumbs truthful even when two broadcasts arrive together.
     */
    suspend fun runInteraction(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        tag: String,
        metadata: suspend () -> Metadata = { Metadata() },
        block: suspend () -> Unit,
    ) {
        var captured = Metadata()
        try {
            withWidgetLock(appWidgetId) {
                captured = metadata()
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val suffix = captured.value.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
            Log.e(TAG, "$tag failed for widget $appWidgetId", e)
            runCatching {
                appLogDao.log(
                    "${tag}_FAIL",
                    "widget=$appWidgetId$suffix ${e.javaClass.simpleName}: ${e.message}",
                    "ERROR",
                )
            }.onFailure { logWriteError ->
                Log.w(TAG, "$tag failed for widget $appWidgetId and FAIL breadcrumb write also failed", logWriteError)
            }
            return
        }

        val suffix = captured.value.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
        runCatching {
            appLogDao.log("${tag}_RENDER_OK", "widget=$appWidgetId$suffix")
        }.onFailure { logWriteError ->
            Log.w(TAG, "$tag rendered for widget $appWidgetId but OK breadcrumb write failed", logWriteError)
        }
    }

    suspend fun runInteraction(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        tag: String,
        metadata: String,
        block: suspend () -> Unit,
    ) = runInteraction(appLogDao, appWidgetId, tag, { Metadata(metadata) }, block)

    suspend fun forEachWidgetIsolated(
        appWidgetIds: IntArray,
        onFailure: suspend (Int, Exception) -> Unit = { _, _ -> },
        render: suspend (Int) -> Unit,
    ) {
        for (appWidgetId in appWidgetIds) {
            try {
                render(appWidgetId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    onFailure(appWidgetId, e)
                } catch (reportingCancellation: CancellationException) {
                    throw reportingCancellation
                } catch (reportingError: Exception) {
                    Log.e(TAG, "Failed to report render failure for widget $appWidgetId", reportingError)
                }
            }
        }
    }

    suspend fun awaitLatestResizeRequest(appWidgetId: Int): Boolean {
        val token = resizeRequestSequence.incrementAndGet()
        latestResizeRequest[appWidgetId] = token
        delay(RESIZE_DEBOUNCE_MS)
        if (latestResizeRequest[appWidgetId] != token) {
            Log.d(TAG, "Resize request superseded for widget $appWidgetId")
            return false
        }
        return true
    }

    fun forgetWidget(appWidgetId: Int) {
        interactionMutexes.remove(appWidgetId)
        latestResizeRequest.remove(appWidgetId)
    }

    fun clearForTesting() {
        interactionMutexes.clear()
        latestResizeRequest.clear()
    }
}
