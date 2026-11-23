package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.remote.HttpPhaseTiming
import com.weatherwidget.shared.util.Log
import com.weatherwidget.test.category.MediumDuration
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The desktop client on a real engine against a local socket server (no network).
 * Pins the desktop pool policy (plans/261116-desktop-okhttp-engine.md): a connection the server closed is
 * replaced without a failed attempt, and idle connections are evicted after the keep-alive. The first
 * also passed on CIO, so it guards the policy, not the engine choice.
 */
@Category(MediumDuration::class)
class DesktopHttpClientStaleConnectionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val retryWarnings = mutableListOf<String>()

    @Before
    fun captureLog() {
        Log.install { priority, tag, msg, _ ->
            println("${priority.name.first()}/$tag: $msg")
            if (tag == "HttpRetry") synchronized(retryWarnings) { retryWarnings += msg }
        }
    }

    @After
    fun restoreLog() = Log.resetToDefault()

    /** Serves one keep-alive response per accepted connection; [closeAfterResponse] then closes that socket. */
    private class OneShotServer(private val connections: Int, private val closeAfterResponse: Boolean) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()
        val requests = AtomicInteger()
        val firstResponseClosed = CountDownLatch(1)
        private val open = mutableListOf<Socket>()

        val url get() = "http://127.0.0.1:${server.localPort}/v1/forecast"

        init {
            thread(isDaemon = true, name = "one-shot-server") {
                runCatching {
                    repeat(connections) { i ->
                        val socket = server.accept()
                        accepted.incrementAndGet()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        requests.incrementAndGet()
                        val body = "ok${i + 1}"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body".toByteArray())
                            flush()
                        }
                        println("server: connection=${i + 1} answered body=$body close=$closeAfterResponse")
                        if (closeAfterResponse) socket.close() else synchronized(open) { open += socket }
                        if (i == 0) firstResponseClosed.countDown()
                    }
                }.onFailure { println("server stopped: ${it::class.simpleName}: ${it.message}") }
            }
        }

        override fun close() {
            synchronized(open) { open.forEach { runCatching { it.close() } } }
            server.close()
        }
    }

    @Test
    fun `a pooled connection the server closed is replaced without a failed attempt`() = runBlocking {
        OneShotServer(connections = 2, closeAfterResponse = true).use { server ->
            val client = DesktopHttpClient.newWeatherClient(json, weatherDao = null, isForeground = false)

            assertEquals("ok1", client.get(server.url).bodyAsText())
            assertTrue(server.firstResponseClosed.await(2, TimeUnit.SECONDS))
            Thread.sleep(100) // let the FIN reach the client before it picks a pooled connection

            val startMs = System.currentTimeMillis()
            val second = client.get(server.url).bodyAsText()
            val elapsedMs = System.currentTimeMillis() - startMs
            println("second=$second elapsedMs=$elapsedMs accepted=${server.accepted.get()} retries=$retryWarnings")

            assertEquals("ok2", second)
            assertEquals(2, server.accepted.get())
            assertTrue("a reused dead connection cost a Ktor retry: $retryWarnings", retryWarnings.isEmpty())
            assertTrue("second call took ${elapsedMs}ms", elapsedMs < 2_000)
            client.close()
        }
    }

    @Test
    fun `idle connections are evicted after the keep-alive so a later fetch opens a new one`() = runBlocking {
        OneShotServer(connections = 2, closeAfterResponse = false).use { server ->
            val okHttp = DesktopHttpClient.newOkHttpClient(keepAliveMs = 200)
            val client = DesktopHttpClient.newWeatherClient(json, weatherDao = null, isForeground = false, okHttp = okHttp)

            assertEquals("ok1", client.get(server.url).bodyAsText())
            // Ktor derives a per-timeout client with newBuilder(); this proves it shares our pool.
            val idleAfterFirst = okHttp.connectionPool.idleConnectionCount()

            val deadline = System.currentTimeMillis() + 3_000
            while (okHttp.connectionPool.idleConnectionCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val idleAfterKeepAlive = okHttp.connectionPool.idleConnectionCount()

            assertEquals("ok2", client.get(server.url).bodyAsText())
            println("idleAfterFirst=$idleAfterFirst idleAfterKeepAlive=$idleAfterKeepAlive accepted=${server.accepted.get()}")

            assertEquals(1, idleAfterFirst)
            assertEquals(0, idleAfterKeepAlive)
            assertEquals("the later fetch must use a new connection", 2, server.accepted.get())
            client.close()
        }
    }

    @Test
    fun `stalled socket emits HTTP_PHASE_FAIL to console and real SQLite app_logs, while healthy call emits none`() = runBlocking {
        val dbDir = Files.createTempDirectory("desktop-http-phase-test")
        val db = DesktopWeatherDatabase(dbDir.resolve("weather.db")).apply { initialize() }
        val dao = DesktopWeatherDao(db)
        val phaseLogs = mutableListOf<String>()
        Log.install { priority, tag, msg, _ ->
            println("${priority.name.first()}/$tag: $msg")
            if (tag == HttpPhaseTiming.LOG_TAG) synchronized(phaseLogs) { phaseLogs += msg }
        }
        com.weatherwidget.shared.util.AppLog.install { tag, msg, level ->
            DesktopAppLogQueue.write("test_app_log") { dao.log(tag, msg, level) }
        }

        // 1. Healthy call emits zero HTTP_PHASE_FAIL lines.
        OneShotServer(connections = 1, closeAfterResponse = true).use { server ->
            val client = DesktopHttpClient.newWeatherClient(json, weatherDao = dao, isForeground = false)
            assertEquals("ok1", client.get(server.url).bodyAsText())
            client.close()
        }
        assertTrue("healthy call must not emit HTTP_PHASE_FAIL: $phaseLogs", phaseLogs.isEmpty())

        // 2. Accept a TCP connection and hold it silently so OkHttp hits socket timeout waiting for TTFB.
        val silentServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val heldSockets = mutableListOf<Socket>()
        thread(isDaemon = true, name = "silent-stall-server") {
            runCatching {
                val s = silentServer.accept()
                synchronized(heldSockets) { heldSockets += s }
                Thread.sleep(5_000)
            }
        }
        try {
            val client = DesktopHttpClient.newWeatherClient(json, weatherDao = dao, isForeground = false)
            val failed = runCatching {
                client.get("http://127.0.0.1:${silentServer.localPort}/v1/forecast") {
                    retry { noRetry() }
                    timeout {
                        connectTimeoutMillis = 1_000
                        socketTimeoutMillis = 300
                        requestTimeoutMillis = 2_000
                    }
                }.bodyAsText()
            }.exceptionOrNull()
            assertTrue("expected timeout failure, got $failed", failed != null)
            client.close()

            // Drain the async app-log queue by waiting briefly for the row to land in SQLite.
            val deadline = System.currentTimeMillis() + 3_000
            var dbRow: String? = null
            while (dbRow == null && System.currentTimeMillis() < deadline) {
                dbRow = dao.getRecentLogs(50).firstOrNull { it.tag == HttpPhaseTiming.LOG_TAG }?.message
                if (dbRow == null) Thread.sleep(25)
            }

            println("phaseLogs=$phaseLogs dbRow=$dbRow")
            assertEquals(1, phaseLogs.size)
            val line = phaseLogs.single()
            assertTrue(line, line.contains("host=127.0.0.1"))
            assertTrue(line, line.contains("scheme=http"))
            assertTrue(line, line.contains("remoteIp=127.0.0.1"))
            assertTrue(line, line.contains("ttfbMs=-1"))
            assertTrue(line, dbRow != null && dbRow.contains("host=127.0.0.1"))
        } finally {
            com.weatherwidget.shared.util.AppLog.resetToDefault()
            synchronized(heldSockets) { heldSockets.forEach { runCatching { it.close() } } }
            silentServer.close()
            dbDir.toFile().deleteRecursively()
        }
    }
}
