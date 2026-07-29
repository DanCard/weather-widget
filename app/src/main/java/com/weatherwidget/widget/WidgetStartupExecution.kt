package com.weatherwidget.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

internal data class TimedResult<T>(
    val value: T,
    val durationMs: Long,
)

/** Times an operation inside its own coroutine rather than around the later await call. */
internal class StartupOperationTimer(
    private val elapsedRealtime: () -> Long,
) {
    fun <T> async(
        scope: CoroutineScope,
        block: suspend () -> T,
    ): Deferred<TimedResult<T>> =
        scope.async {
            val startMs = elapsedRealtime()
            val value = block()
            TimedResult(value, elapsedRealtime() - startMs)
        }
}

internal data class WidgetStartupOutcome(
    val appWidgetId: Int,
    val failure: Throwable?,
)

/**
 * Runs startup paints in parallel while containing ordinary failures to the affected widget.
 * Parent cancellation remains structured and propagates to every child.
 */
internal suspend fun runStartupTasksIsolated(
    appWidgetIds: IntArray,
    onStarted: (Int, Job) -> Unit = { _, _ -> },
    block: suspend (Int) -> Unit,
): List<WidgetStartupOutcome> =
    supervisorScope {
        appWidgetIds
            .map { appWidgetId ->
                async {
                    try {
                        block(appWidgetId)
                        WidgetStartupOutcome(appWidgetId, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        WidgetStartupOutcome(appWidgetId, e)
                    }
                }.also { onStarted(appWidgetId, it) }
            }.awaitAll()
    }
