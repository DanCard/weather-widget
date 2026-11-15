package com.weatherwidget.widget

import android.os.Process
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The threads background sync work runs on: a small pool at background priority, so the paints and
 * tap handlers on the ordinary dispatchers win the CPU whenever they want it.
 *
 * Before this, a sync shared `Dispatchers.Default` with them and nothing set a priority. On a boot
 * (Pixel 7 Pro, 2026-11-15) three syncs ran beside the cache-first paint and a 0.5 s read was still
 * unfinished 39 s later; a warm daily recompute alone is 12-18 s of CPU per day
 * (`performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`, phase 2).
 *
 * What it does not cover: Room runs every query on its own executor and Ktor on its engine's
 * threads, whichever dispatcher the caller is on. This pool governs the CPU work between those
 * calls — parsing, merging, the blend — not database or network waits.
 *
 * Paints started by a sync hop back off it ([WidgetPaintCoordinator]); a finished sync's repaint is
 * the user-visible part and must not inherit the low priority.
 */
internal object SyncDispatcher {
    private const val TAG = "SyncDispatcher"

    /** Parallel fetches are suspending, so two threads carry them; more would crowd the paints again. */
    const val THREADS = 2

    /** `SYNC_STAGE … thread=` shows this, which is how a log tells which pool ran a stage. */
    const val THREAD_NAME_PREFIX = "sync-pool-"

    val dispatcher: CoroutineDispatcher by lazy { create() }

    /** [setPriority] runs on each pool thread as it starts; injected so a JVM test can observe it. */
    internal fun create(
        threads: Int = THREADS,
        setPriority: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
    ): CoroutineDispatcher = Executors.newFixedThreadPool(threads, factory(setPriority)).asCoroutineDispatcher()

    private fun factory(setPriority: () -> Unit): ThreadFactory {
        val next = AtomicInteger(1)
        return ThreadFactory { task ->
            Thread(
                {
                    // A pool that could not lower its priority still works; say so and carry on.
                    try {
                        setPriority()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not lower sync thread priority", e)
                    }
                    task.run()
                },
                THREAD_NAME_PREFIX + next.getAndIncrement(),
            ).apply { isDaemon = true }
        }
    }
}
