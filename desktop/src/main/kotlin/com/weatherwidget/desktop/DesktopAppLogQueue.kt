package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * app_logs rows and usage counters written in order on one background thread, each stamped when it was
 * logged, for writes from code that must not wait on the database.
 *
 * The daemon and UI processes write one SQLite file, so an insert can wait up to the 5 s busy timeout
 * behind the other process's transaction. A `CURR_TEMP_RESULT` row written inline held the daemon's
 * `source_change` cache reload and the UI's own reload for the full timeout, so a source switch showed
 * the new graph 5.6 s late (2026-10-29, plans/261029-shared-source-switch-runner.md).
 *
 * Fetches crowded the lock the same way: every HTTP response wrote a `network_usage` row and an
 * `api_usage_stats` row inline, so a forecast burst queued parallel requests behind each other for up to
 * 1.9 s (DB_WRITE_SLOW, 2026-10-29). Those go through [write] with the service's own DAO.
 *
 * [log] uses the DAO each process installs; until then (tests, early startup) rows are dropped. A full
 * queue drops the write with a console warning rather than block the caller.
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

    /** Queues one row to the installed DAO; returns at once. [DesktopWeatherDao.log] reports its own failures. */
    fun log(tag: String, message: String, level: String = "INFO") {
        val target = dao ?: return
        log(target, tag, message, level)
    }

    /** Queues one row to [dao], stamped now. */
    fun log(dao: DesktopWeatherDao, tag: String, message: String, level: String = "INFO") {
        val timestampMs = System.currentTimeMillis()
        executor.execute { dao.log(tag, message, level, timestampMs) }
    }

    /**
     * Queues [what], a write that takes its time from the caller (capture it before queuing). A failure
     * is logged with [what]'s [label] and never reaches the caller.
     */
    fun write(label: String, what: () -> Unit) {
        executor.execute {
            try {
                what()
            } catch (e: Exception) {
                Log.w(TAG, "queued write failed: $label", e)
            }
        }
    }

    /** Waits until every row queued so far is written (tests). */
    fun drain(timeoutMs: Long = 10_000) {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
