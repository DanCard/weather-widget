package com.weatherwidget.desktop

import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.shared.util.Log
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Serves weather markup over a Unix Domain Socket for the XFCE genmon panel plugin.
 * This replaces the legacy Python-based SQLite polling with a lightweight IPC push/pull.
 */
class PanelIpcServer(private val appDataDir: Path) {
    private val currentMarkup = AtomicReference("<txt>--</txt>")
    private var serverThread: Thread? = null

    fun start() {
        if (serverThread != null) return
        
        serverThread = thread(isDaemon = true, name = "PanelIpcServer") {
            try {
                val socketPath = appDataDir.resolve("weather.sock")
                Files.deleteIfExists(socketPath)
                
                val address = UnixDomainSocketAddress.of(socketPath)
                val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address)
                
                Log.i(TAG, "Panel IPC server listening on $socketPath")

                while (!Thread.interrupted()) {
                    try {
                        val clientChannel = serverChannel.accept()
                        val markup = currentMarkup.get()
                        clientChannel.write(ByteBuffer.wrap(markup.toByteArray()))
                        clientChannel.close()
                    } catch (e: Exception) {
                        if (Thread.interrupted()) break
                        Log.e(TAG, "Error accepting IPC connection: $e")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Panel IPC server failed: $e")
            }
        }
    }

    fun update(forecast: ForecastResult?, dataStatus: DataStatus, config: DesktopConfig?) {
        val temp = forecast?.currentTemp
        val body = if (temp != null) String.format(Locale.US, "%.1f°", temp) else "--"
        
        val isStale = dataStatus is DataStatus.Stale
        val color = if (isStale || temp == null) "#888888" else "#FFD500"
        
        // Tooltip detail matches the legacy python script logic
        val detail = if (forecast != null) {
            val obsAt = forecast.currentObservedAt
            if (obsAt != null && (System.currentTimeMillis() - obsAt) <= 30 * 60 * 1000L) {
                "measured"
            } else {
                "interpolated"
            }
        } else if (config == null) "not configured" else "no data"
        
        val ageStr = when (dataStatus) {
            is DataStatus.Live -> "just now"
            is DataStatus.Stale -> formatRelativeTime(dataStatus.updatedAt)
            else -> "unknown"
        }
        
        val tooltip = "Weather Widget — $detail $ageStr"
        val showTrigger = appDataDir.resolve(".show").toAbsolutePath().toString()
        val clickCmd = "touch $showTrigger"
        
        val markup = """
            <txt><span font='Sans Bold 20' foreground='$color' line_height='0.6'>$body</span></txt>
            <tool>$tooltip</tool>
            <txtclick>$clickCmd</txtclick>
        """.trimIndent()

        currentMarkup.set(markup)
    }

    private fun formatRelativeTime(epochMs: Long): String {
        val elapsed = System.currentTimeMillis() - epochMs
        val minutes = elapsed / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }

    companion object {
        private const val TAG = "PanelIpcServer"
    }
}
