package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * The window → daemon fetch channel over a real Unix socket: the daemon runs the fetch on its own scope,
 * so a window process that goes away mid-fetch costs nothing (plans/261112-desktop-fetches-run-in-daemon.md).
 */
@Category(ShortDuration::class)
class DaemonFetchTest {
    private lateinit var dir: Path
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private var server: DaemonFetchServer? = null

    private val request = FetchRequest(
        FetchRequest.Kind.FORECAST, "OPEN_METEO", 37.42, -122.08, "history", "user_refresh:history", userRefresh = true,
    )

    @Before
    fun setUp() {
        // Short path: Unix socket paths are capped near 108 bytes.
        dir = Files.createTempDirectory(Path.of("/tmp"), "dft")
    }

    @After
    fun tearDown() {
        server?.close()
        daemonScope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun startServer(execute: suspend (FetchRequest) -> FetchResponse) {
        server = DaemonFetchServer(dir, daemonScope, { tag, message, _ -> logs += "$tag $message".also(::println) }, execute).apply { start() }
        val socket = dir.resolve(FETCH_REQUEST_SOCKET)
        val deadline = System.currentTimeMillis() + 5_000
        while (!Files.exists(socket) && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `every kind round-trips through the wire format`() {
        FetchRequest.Kind.entries.forEach { kind ->
            val r = request.copy(kind = kind, date = "2026-11-12", backHours = 48, observationWindow = true, userLocationChange = true)
            assertEquals(r, FetchWire.decodeRequest(FetchWire.encode(r)))
        }
        val failed = FetchResponse(ok = false, errorClass = "HttpRequestTimeoutException", errorMessage = "Request timeout has expired")
        assertEquals(failed, FetchWire.decodeResponse(FetchWire.encode(failed)))
    }

    @Test
    fun `the client gets the daemon's reply and a failure is rethrown with its class`() = runBlocking {
        startServer { r -> if (r.kind == FetchRequest.Kind.HOURLY_DAY) FetchResponse(ok = true, changed = true) else throw java.net.SocketTimeoutException("timed out") }
        val client = DaemonFetchClient(dir)

        assertEquals(FetchResponse(ok = true, changed = true), client.send(request.copy(kind = FetchRequest.Kind.HOURLY_DAY, date = "2026-11-13")))
        try {
            client.send(request)
            fail("expected the daemon's failure")
        } catch (e: DaemonFetchException) {
            assertEquals("SocketTimeoutException", e.errorClass)
        }
    }

    @Test
    fun `a window that disconnects mid-fetch does not cancel it`() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        startServer {
            release.await()
            finished.complete(Unit)
            FetchResponse(ok = true)
        }
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val waiting = windowScope.async { DaemonFetchClient(dir).send(request) }
        withTimeout(5_000) { while (logs.none { it.contains("FETCH_REQUEST start") }) delay(10) }

        windowScope.cancel() // the window process exits
        release.complete(Unit)

        withTimeout(5_000) { finished.await() }
        withTimeout(5_000) { while (logs.none { it.contains("FETCH_REQUEST done") }) delay(10) }
        assertTrue(waiting.isCancelled)
        assertTrue(logs.any { it.contains("done kind=FORECAST source=OPEN_METEO origin=history") && it.contains("ok=true") })
    }

    @Test
    fun `a bad request gets an error reply, not a dropped connection`() {
        startServer { FetchResponse(ok = true) }
        SocketChannel.open(UnixDomainSocketAddress.of(dir.resolve(FETCH_REQUEST_SOCKET))).use { channel ->
            channel.write(ByteBuffer.wrap("not json\n".toByteArray()))
            val reply = java.nio.channels.Channels.newReader(channel, Charsets.UTF_8).buffered().readLine()
            assertEquals("BadRequest", FetchWire.decodeResponse(reply).errorClass)
        }
    }

    @Test
    fun `no daemon - the client fails as unavailable`() = runBlocking {
        try {
            DaemonFetchClient(dir).send(request)
            fail("expected unavailable")
        } catch (e: DaemonFetchException) {
            assertEquals(DaemonFetchClient.DAEMON_UNAVAILABLE, e.errorClass)
        }
    }
}
