package com.weatherwidget.shared.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a fetch the user asked for so that it outlives the screen that asked.
 *
 * A screen only [awaits][UserRefresh.await] the fetch. Closing the screen cancels the waiting, never
 * the fetch: its rows are still stored, and [start]'s `afterDetached` does the follow-up the screen
 * can no longer do (repaint the widget). Both platforms broke this the same way — desktop's Refresh
 * on the Settings window's scope (2026-07), Android's Observations Refresh on `lifecycleScope`
 * (2026-10-13) — so the rule lives here, and `UserRefreshCallSites` lets each platform's guard test
 * check that every user fetch goes through it
 * (plans/261013-observations-refresh-survives-closing-the-screen.md).
 *
 * @param scope app-lifetime scope (Android `@ApplicationIoScope`, desktop's application `uiScope`).
 * @param log `(tag, message, level)` into the platform's app log.
 */
class UserRefreshRunner(
    private val scope: CoroutineScope,
    private val log: (tag: String, message: String, level: String) -> Unit,
) {
    fun <T> start(
        origin: String,
        afterDetached: suspend (T) -> Unit = {},
        fetch: suspend () -> T,
    ): UserRefresh<T> {
        val state = AtomicInteger(ATTACHED)
        // A supervisor between the fetch and [scope]: a failing fetch must not cancel a plain-Job
        // scope (desktop's uiScope) and everything else it runs. The failure reaches the waiter.
        val deferred = scope.async(SupervisorJob(scope.coroutineContext[Job])) {
            val result = try {
                fetch()
            } catch (e: Throwable) {
                if (!state.compareAndSet(ATTACHED, DONE) && e !is CancellationException) {
                    log(TAG, "origin=$origin outcome=failed ${e::class.simpleName}: ${e.message}", "WARN")
                }
                throw e
            }
            if (!state.compareAndSet(ATTACHED, DONE)) finishDetached(origin, result, afterDetached)
            result
        }
        return UserRefresh(origin, deferred, state, afterDetached)
    }

    inner class UserRefresh<T> internal constructor(
        private val origin: String,
        private val deferred: Deferred<T>,
        private val state: AtomicInteger,
        private val afterDetached: suspend (T) -> Unit,
    ) {
        /** The fetch's result or its exception. Cancelling the caller detaches; the fetch runs on. */
        suspend fun await(): T =
            try {
                deferred.await()
            } catch (e: CancellationException) {
                if (deferred.isCancelled) throw e // the app scope itself is gone: nothing to hand on
                if (state.compareAndSet(ATTACHED, DETACHED)) {
                    log(TAG, "origin=$origin outcome=waiting", "INFO")
                } else if (deferred.isCompleted && deferred.getCompletionExceptionOrNull() == null) {
                    // Landed while the caller was being cancelled: no one will show it, so do the
                    // detached follow-up here.
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    val result = deferred.getCompleted()
                    scope.launch(SupervisorJob(scope.coroutineContext[Job])) { finishDetached(origin, result, afterDetached) }
                }
                throw e
            }
    }

    private suspend fun <T> finishDetached(origin: String, result: T, afterDetached: suspend (T) -> Unit) {
        try {
            afterDetached(result)
            log(TAG, "origin=$origin outcome=completed", "INFO")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, "origin=$origin outcome=completed afterDetachedFailed=${e::class.simpleName}: ${e.message}", "WARN")
        }
    }

    companion object {
        const val TAG = "USER_REFRESH_DETACHED"
        private const val ATTACHED = 0
        private const val DETACHED = 1
        private const val DONE = 2
    }
}
