package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * app_logs rows written in order on one background thread, each stamped when it was logged, for
 * breadcrumbs written from code that must not wait on the database.
 *
 * The daemon and UI processes write one SQLite file, so an insert can wait up to the 5 s busy timeout
 * behind the other process's transaction. A `CURR_TEMP_RESULT` row written inline held the daemon's
 * `source_change` cache reload and the UI's own reload for the full timeout, so a source switch showed
 * the new graph 5.6 s late (2026-10-29, plans/261029-shared-source-switch-runner.md).
 *
 * Each process installs its DAO. Until then (tests, early startup) rows are dropped. A full queue drops
 * the row with a console warning rather than block the caller.
 */
internal object DesktopAppLogQueue {
    private const val TAG = "DesktopAppLogQueue"
    const val CAPACITY = 1_000

    @Volatile
    private var dao: DesktopWeatherDao? = null

    private val executor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(CAPACITY),
        { r -> Thread(r, "app-log-writer").apply { isDaemon = true } },
        { _, _ -> Log.w(TAG, "app_logs queue full ($CAPACITY); row dropped") },
    )

    fun install(dao: DesktopWeatherDao?) {
        this.dao = dao
    }

    /** Queues one row; returns at once. [DesktopWeatherDao.log] reports its own failures. */
    fun log(tag: String, message: String, level: String = "INFO") {
        val target = dao ?: return
        val timestampMs = System.currentTimeMillis()
        executor.execute { target.log(tag, message, level, timestampMs) }
    }

    /** Waits until every row queued so far is written (tests). */
    fun drain(timeoutMs: Long = 10_000) {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
