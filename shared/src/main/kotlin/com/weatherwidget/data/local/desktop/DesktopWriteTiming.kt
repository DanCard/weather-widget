package com.weatherwidget.data.local.desktop

import com.weatherwidget.shared.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/**
 * Names slow desktop SQLite writes as `DB_WRITE_SLOW` console lines, so a lock stall identifies both its
 * victim and its holder. The daemon and UI processes write one WAL database, so a write waits on the
 * other's open write transaction for up to the 5 s busy timeout. That froze a popup source switch for
 * 5.3 s on 2026-10-29, and nothing said who held the lock.
 *
 * [wrap] proxies a connection; nothing in the DAO changes:
 * - A transaction (`autoCommit = false`): `waitMs` is the `BEGIN IMMEDIATE` that sqlite-jdbc issues
 *   inside `setAutoCommit(false)`; `holdMs` runs from the lock until `close()` or `setAutoCommit(true)`,
 *   not `commit()`, because sqlite-jdbc begins the next IMMEDIATE transaction inside `commit()` and keeps
 *   the lock until the connection closes (checked against 3.53.1.0).
 * - An autocommit write (app_logs inserts, deletes, VACUUM): one `ms`, the lock wait plus the write.
 * - Opening a connection (it sets `journal_mode=WAL`): `kind=open ms=`.
 *
 * A wait of [waitThresholdMs] or more marks the victim (about where a stalled click feels laggy); a hold
 * of [holdThresholdMs] or more marks a culprit that can push a waiter past it. The lines are console
 * only: writing them to app_logs would wait on the lock they report. Reads are not timed.
 * plans/261029-shared-source-switch-runner.md
 */
class DesktopWriteTiming(
    val waitThresholdMs: Long = WAIT_THRESHOLD_MS,
    val holdThresholdMs: Long = HOLD_THRESHOLD_MS,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val report: (String) -> Unit = { Log.w(TAG, it) },
) {
    fun wrap(connection: Connection): Connection =
        proxy(Connection::class.java, ConnectionTiming(connection))

    /** Times [create], which opens the connection, and returns it wrapped. */
    fun open(create: () -> Connection): Connection {
        val startMs = elapsedMs()
        val connection = create()
        val ms = elapsedMs() - startMs
        if (ms >= waitThresholdMs) report("$SLOW_TAG kind=open caller=${caller()} ms=$ms ${where()}")
        return wrap(connection)
    }

    private inner class ConnectionTiming(private val target: Connection) : InvocationHandler {
        private var waitMs = 0L
        private var lockedAtMs: Long? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "setAutoCommit" -> {
                val enable = args!![0] as Boolean
                if (!enable && lockedAtMs == null && target.autoCommit) {
                    val startMs = elapsedMs()
                    try {
                        call(target, method, args)
                    } catch (e: Throwable) {
                        // The BEGIN IMMEDIATE that waited out the busy timeout: the stall that matters most.
                        report("$SLOW_TAG kind=txn outcome=failed caller=${caller()} waitMs=${elapsedMs() - startMs} ${where()}")
                        throw e
                    }
                    lockedAtMs = elapsedMs()
                    waitMs = lockedAtMs!! - startMs
                    null
                } else {
                    call(target, method, args).also { if (enable) release() }
                }
            }
            "close" -> call(target, method, args).also { release() }
            "prepareStatement" -> {
                val statement = call(target, method, args) as PreparedStatement
                if (target.autoCommit && isWrite(args!![0] as String)) {
                    proxy(PreparedStatement::class.java, AutocommitTiming(statement, prepared = true))
                } else {
                    statement
                }
            }
            "createStatement" -> {
                val statement = call(target, method, args) as Statement
                if (target.autoCommit) proxy(Statement::class.java, AutocommitTiming(statement, prepared = false)) else statement
            }
            else -> call(target, method, args)
        }

        private fun release() {
            val lockedAt = lockedAtMs ?: return
            lockedAtMs = null
            val holdMs = elapsedMs() - lockedAt
            if (waitMs >= waitThresholdMs || holdMs >= holdThresholdMs) {
                report("$SLOW_TAG kind=txn caller=${caller()} waitMs=$waitMs holdMs=$holdMs ${where()}")
            }
        }
    }

    /** One autocommit statement: a prepared write, or a plain statement whose SQL is checked per call. */
    private inner class AutocommitTiming(private val target: Statement, private val prepared: Boolean) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (!method.name.startsWith("execute") || method.name == "executeQuery") return call(target, method, args)
            val sql = args?.firstOrNull() as? String
            if (!prepared && sql != null && !isWrite(sql)) return call(target, method, args)
            val startMs = elapsedMs()
            var failed = true
            try {
                return call(target, method, args).also { failed = false }
            } finally {
                val ms = elapsedMs() - startMs
                if (failed || ms >= waitThresholdMs) {
                    val outcome = if (failed) " outcome=failed" else ""
                    report("$SLOW_TAG kind=autocommit$outcome caller=${caller()} ms=$ms ${where()}")
                }
            }
        }
    }

    companion object {
        const val SLOW_TAG = "DB_WRITE_SLOW"
        const val WAIT_THRESHOLD_MS = 200L
        const val HOLD_THRESHOLD_MS = 500L
        private const val TAG = "DesktopWriteTiming"
        private val WRITE_VERBS = listOf("INSERT", "UPDATE", "DELETE", "REPLACE", "VACUUM", "CREATE", "DROP", "ALTER")

        internal fun isWrite(sql: String): Boolean {
            val head = sql.trimStart().take(8).uppercase()
            return WRITE_VERBS.any { head.startsWith(it) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

        private fun call(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        private fun where() = "thread=${Thread.currentThread().name} pid=${ProcessHandle.current().pid()}"

        /**
         * The DAO method that wrote and the two app frames above it (`DesktopWeatherDao.log<-Caller.method<-…`):
         * the first frames outside JDBC, proxies, the JDK, coroutines, this class and `getConnection`.
         * `DesktopWeatherDao.log` alone does not say who was logging. Walked only when a line is reported.
         */
        private fun caller(): String = Throwable().stackTrace.asSequence().filter { frame ->
            val name = frame.className
            !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("kotlin.") &&
                !name.startsWith("kotlinx.") && !name.startsWith("org.sqlite.") && !name.startsWith("com.sun.proxy") &&
                !name.contains("\$Proxy") &&
                name != DesktopWriteTiming::class.java.name && !name.startsWith("${DesktopWriteTiming::class.java.name}$") &&
                !(name == DesktopWeatherDatabase::class.java.name && frame.methodName == "getConnection") &&
                !frame.methodName.endsWith("\$default") // Kotlin's default-argument bridge, not a caller
        }.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            .take(3).toList().ifEmpty { listOf("unknown") }.joinToString("<-")
    }
}
