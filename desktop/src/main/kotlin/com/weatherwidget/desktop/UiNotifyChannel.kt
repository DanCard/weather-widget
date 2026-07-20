package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Daemon → UI push notification over a dedicated Unix Domain Socket (`ui-notify.sock`).
 *
 * This is the non-lossy counterpart to the `.data-updated` file trigger: file-touch + Java
 * `WatchService` coalesces/drops events (especially across suspend) and the watcher can die on a
 * failed `key.reset()`, so the file path alone let the popup go stale-forever. A live socket the UI
 * holds open is inherently reliable — and a dropped connection (daemon restart, post-resume) forces
 * the client to reconnect, which is itself a reload point. The file trigger stays in place as a
 * belt-and-suspenders fallback; this just adds a second, more reliable signal.
 *
 * Distinct from [PanelIpcServer]/`weather.sock`, which serves the XFCE genmon panel (pull-based),
 * not the Compose UI process.
 *
 * Protocol: the server writes a single byte per data-change event; the client treats every received
 * byte (and every successful connect) as "reload the cache". No payload — the UI re-reads the DB via
 * `loadCached()`, so the socket only needs to carry the *fact* that something changed.
 */
const val UI_NOTIFY_SOCKET = "ui-notify.sock"

private const val NOTIFY_BYTE: Byte = 'U'.code.toByte()

/** Daemon side: accepts UI connections and pushes a byte on each data change. */
class UiNotifyServer(private val appDataDir: Path) {
    private val clients = CopyOnWriteArrayList<SocketChannel>()
    private var serverChannel: ServerSocketChannel? = null
    private var serverThread: Thread? = null

    fun start() {
        if (serverThread != null) return
        serverThread = thread(isDaemon = true, name = "UiNotifyServer") {
            try {
                val socketPath = appDataDir.resolve(UI_NOTIFY_SOCKET)
                Files.deleteIfExists(socketPath)
                val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    .bind(UnixDomainSocketAddress.of(socketPath))
                serverChannel = channel
                Log.i(TAG, "UI notify server listening on $socketPath")
                while (!Thread.interrupted()) {
                    try {
                        val client = channel.accept()
                        clients.add(client)
                        Log.i(TAG, "UI notify client connected (${clients.size} total)")
                    } catch (e: Exception) {
                        if (Thread.interrupted()) break
                        Log.e(TAG, "UI notify accept failed: $e")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UI notify server failed: $e")
            }
        }
    }

    /** Writes a single notify byte to every connected UI client, dropping any that have died. */
    fun pushDataUpdated() {
        if (clients.isEmpty()) return
        val dead = mutableListOf<SocketChannel>()
        for (client in clients) {
            try {
                client.write(ByteBuffer.wrap(byteArrayOf(NOTIFY_BYTE)))
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        for (client in dead) {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    fun close() {
        serverThread?.interrupt()
        runCatching { serverChannel?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    companion object {
        private const val TAG = "UiNotifyServer"
    }
}

/**
 * UI side: holds a connection to [UiNotifyServer] open and calls [onNotify] whenever the daemon
 * signals a data change — and once on every (re)connect, so a change that landed while we were
 * disconnected (daemon restart, suspend) is never missed. Reconnects with a fixed backoff.
 */
class UiNotifyClient(
    private val appDataDir: Path,
    private val onNotify: (reason: String) -> Unit,
) {
    @Volatile private var running = false
    private var clientThread: Thread? = null

    fun start() {
        if (clientThread != null) return
        running = true
        clientThread = thread(isDaemon = true, name = "UiNotifyClient") {
            val socketPath = appDataDir.resolve(UI_NOTIFY_SOCKET)
            val buf = ByteBuffer.allocate(16)
            while (running) {
                var channel: SocketChannel? = null
                try {
                    channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
                    Log.i(TAG, "Connected to UI notify server")
                    // A change may have landed while we were disconnected — reload on connect.
                    onNotify("connect")
                    while (running) {
                        buf.clear()
                        val n = channel.read(buf)
                        if (n < 0) break // server closed → reconnect
                        if (n > 0) onNotify("push")
                    }
                } catch (e: Exception) {
                    // Socket missing (daemon not up yet) or dropped — retry after a pause.
                } finally {
                    runCatching { channel?.close() }
                }
                if (running) Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
    }

    fun close() {
        running = false
        clientThread?.interrupt()
    }

    companion object {
        private const val TAG = "UiNotifyClient"
        private const val RECONNECT_DELAY_MS = 3_000L
    }
}
