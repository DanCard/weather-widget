package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Every desktop fetch that stores data runs in the daemon. The window process exits 400 ms after its
 * last window closes, which killed any fetch it was running (plans/261112-desktop-fetches-run-in-daemon.md).
 * The UI sends one [FetchRequest] line over [FETCH_REQUEST_SOCKET] and waits for one [FetchResponse]
 * line; the daemon runs the fetch on its own scope, so a UI that goes away mid-fetch costs nothing.
 */
const val FETCH_REQUEST_SOCKET = "fetch-request.sock"

@Serializable
data class FetchRequest(
    val kind: Kind,
    val sourceId: String,
    val lat: Double,
    val lon: Double,
    /** Where it came from (`history`, `popup_open`, …), for the log. */
    val origin: String,
    /** The `reason` the repository logs (`REFRESH_ENTER`). */
    val reason: String,
    val userRefresh: Boolean = false,
    /** [Kind.FORECAST]: also fill the 7-day observation window, as Refresh does. */
    val observationWindow: Boolean = false,
    /** [Kind.FORECAST]: a new site the user just picked. */
    val userLocationChange: Boolean = false,
    /** [Kind.HOURLY_DAY]: ISO date of the tapped day. */
    val date: String? = null,
    /** [Kind.HISTORY_DEPTH]: hours back the graph needs. */
    val backHours: Int? = null,
) {
    enum class Kind { FORECAST, OTHER_SOURCES, OBSERVATIONS, HOURLY_DAY, HISTORY_DEPTH }

    fun logLine(): String = "kind=$kind source=$sourceId origin=$origin reason=$reason"
}

@Serializable
data class FetchResponse(
    val ok: Boolean,
    /** [FetchRequest.Kind.HOURLY_DAY]/[FetchRequest.Kind.HISTORY_DEPTH]: whether new rows were stored. */
    val changed: Boolean = false,
    val errorClass: String? = null,
    val errorMessage: String? = null,
)

/** The failure a [FetchResponse] reports, rethrown on the UI side so its callers keep their catch blocks. */
class DaemonFetchException(val errorClass: String, message: String?) : Exception("$errorClass: $message")

internal object FetchWire {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(request: FetchRequest): String = json.encodeToString(FetchRequest.serializer(), request)

    fun decodeRequest(line: String): FetchRequest = json.decodeFromString(FetchRequest.serializer(), line)

    fun encode(response: FetchResponse): String = json.encodeToString(FetchResponse.serializer(), response)

    fun decodeResponse(line: String): FetchResponse = json.decodeFromString(FetchResponse.serializer(), line)
}

/**
 * Daemon side: one request and one reply per connection. [execute] runs on [scope] in its own job: the
 * connection only carries the reply, so a client that closes early never cancels the fetch.
 */
internal class DaemonFetchServer(
    private val appDir: Path,
    private val scope: CoroutineScope,
    private val log: (tag: String, message: String, level: String) -> Unit,
    private val execute: suspend (FetchRequest) -> FetchResponse,
) {
    private var serverChannel: ServerSocketChannel? = null
    private var serverThread: Thread? = null

    fun start() {
        if (serverThread != null) return
        serverThread = thread(isDaemon = true, name = "DaemonFetchServer") {
            try {
                val socketPath = appDir.resolve(FETCH_REQUEST_SOCKET)
                Files.deleteIfExists(socketPath)
                val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socketPath))
                serverChannel = channel
                Log.i(TAG, "Fetch request server listening on $socketPath")
                while (!Thread.interrupted()) {
                    val client = try {
                        channel.accept()
                    } catch (e: Exception) {
                        if (Thread.interrupted() || !channel.isOpen) break
                        Log.e(TAG, "Fetch request accept failed: $e")
                        continue
                    }
                    scope.launch(Dispatchers.IO) { serve(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch request server failed: $e")
            }
        }
    }

    private suspend fun serve(client: SocketChannel) {
        client.use { channel ->
            val line = runCatching { Channels.newReader(channel, Charsets.UTF_8).buffered().readLine() }.getOrNull()
            if (line == null) {
                log(TAG_LOG, "dropped reason=no_request", "WARN")
                return
            }
            val request = try {
                FetchWire.decodeRequest(line)
            } catch (e: Exception) {
                log(TAG_LOG, "rejected reason=bad_request ${e::class.simpleName}: ${e.message}", "WARN")
                reply(channel, FetchResponse(ok = false, errorClass = "BadRequest", errorMessage = e.message))
                return
            }
            val startedAtMs = System.currentTimeMillis()
            log(TAG_LOG, "start ${request.logLine()}", "INFO")
            val response = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FetchResponse(ok = false, errorClass = e::class.simpleName ?: "Exception", errorMessage = e.message)
            }
            log(
                TAG_LOG,
                "done ${request.logLine()} ok=${response.ok} changed=${response.changed} ms=${System.currentTimeMillis() - startedAtMs}" +
                    (response.errorClass?.let { " error=$it" } ?: ""),
                if (response.ok) "INFO" else "WARN",
            )
            // The UI may be gone by now (its last window closed): the rows are stored either way.
            if (!reply(channel, response)) log(TAG_LOG, "reply_dropped ${request.logLine()}", "INFO")
        }
    }

    private fun reply(channel: SocketChannel, response: FetchResponse): Boolean = runCatching {
        val bytes = (FetchWire.encode(response) + "\n").toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }.isSuccess

    fun close() {
        serverThread?.interrupt()
        runCatching { serverChannel?.close() }
    }

    companion object {
        private const val TAG = "DaemonFetchServer"
        const val TAG_LOG = "FETCH_REQUEST"
    }
}

/** UI side: sends [request] to the daemon and waits for its reply. Throws [DaemonFetchException] on a failed fetch. */
internal class DaemonFetchClient(private val appDir: Path) {
    suspend fun send(request: FetchRequest): FetchResponse = withContext(Dispatchers.IO) {
        val response = try {
            runInterruptible {
                SocketChannel.open(UnixDomainSocketAddress.of(appDir.resolve(FETCH_REQUEST_SOCKET))).use { channel ->
                    val bytes = ByteBuffer.wrap((FetchWire.encode(request) + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    val line = Channels.newReader(channel, Charsets.UTF_8).buffered().readLine()
                        ?: throw DaemonFetchException(DAEMON_UNAVAILABLE, "daemon closed the connection")
                    FetchWire.decodeResponse(line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DaemonFetchException) {
            throw e
        } catch (e: java.io.IOException) {
            throw DaemonFetchException(DAEMON_UNAVAILABLE, e.message)
        }
        if (!response.ok) throw DaemonFetchException(response.errorClass ?: "Exception", response.errorMessage)
        response
    }

    companion object {
        const val DAEMON_UNAVAILABLE = "DaemonUnavailable"
    }
}
