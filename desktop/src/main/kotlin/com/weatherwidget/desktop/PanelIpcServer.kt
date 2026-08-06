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
class PanelIpcServer(
    private val appDataDir: Path,
    private val markupProvider: PanelIpcServer.() -> String
) {
    private var serverThread: Thread? = null

    private var cachedPluginId: String? = null
    private var lastLookupAttemptMs = 0L

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
                        val markup = markupProvider()
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

    private fun triggerPanelRefresh() {
        val now = System.currentTimeMillis()
        if (cachedPluginId == null && now - lastLookupAttemptMs >= LOOKUP_RETRY_INTERVAL_MS) {
            lastLookupAttemptMs = now
            cachedPluginId = findGenmonPluginId()
            if (cachedPluginId != null) {
                Log.i(TAG, "Discovered XFCE Genmon plugin ID: $cachedPluginId")
            }
        }

        val id = cachedPluginId ?: return
        try {
            ProcessBuilder("xfce4-panel", "--plugin-event=genmon-$id:refresh:bool:true").start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send refresh signal to xfce4-panel: $e")
        }
    }

    fun triggerRefresh() {
        triggerPanelRefresh()
    }

    fun generateMarkup(
        forecast: ForecastResult?,
        currentTemp: Float?,
        deltaFromYesterday: Float?,
        dataStatus: DataStatus,
        config: DesktopConfig?
    ): String {
        val useCelsius = config?.useCelsius
            ?: com.weatherwidget.shared.util.UnitDefaults.defaultUseCelsius(Locale.getDefault())
        val temp = currentTemp
        val displayTemp = if (useCelsius && temp != null) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(temp) else temp
        val body = if (displayTemp != null) String.format(Locale.US, "%.1f°", displayTemp) else "--"

        // The distributable can be deleted out from under us (a clean/rebuild) while this daemon
        // keeps running on its deleted inode. We still serve fresh data here, but the daemon can no
        // longer spawn the popup, so every genmon click fails silently. Surface that on the panel
        // itself (and rewire the click to explain the fix) instead of leaving the user guessing.
        if (isUiLauncherMissing()) {
            return missingLauncherMarkup(body)
        }

        val isStale = dataStatus is DataStatus.Stale
        val color = if (isStale || temp == null) STALE_COLOR else LIVE_COLOR

        // Mirror the popup header (Main.kt): show the delta from yesterday, formatted "%+.1f"
        // (no degree symbol), only when it is non-trivial. Always orange, like the header.
        val deltaText = deltaFromYesterday
            ?.takeIf { kotlin.math.abs(it) >= 0.1f }
            ?.let {
                val displayDelta = if (useCelsius) it / 1.8f else it
                String.format(Locale.US, "%+.1f", displayDelta)
            }

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

        return buildPanelMarkup(body, color, deltaText, tooltip, clickCmd)
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
        private const val LOOKUP_RETRY_INTERVAL_MS = 60_000L

        const val LIVE_COLOR = "#FFD500"   // high-contrast yellow, matches the tray icon
        const val STALE_COLOR = "#888888"  // grayed when data is stale / missing
        const val DELTA_COLOR = "#FF6B35"  // orange, matches the popup header delta
        const val WARN_COLOR = "#FF3333"   // red, for "app files removed — restart needed"

        /**
         * Panel markup shown when the UI launcher binary was deleted (see [isUiLauncherMissing]).
         * Keeps the live temperature but flags it with a red ⚠, explains the fix in the tooltip, and
         * rewires the click away from the now-dead `touch .show` (which can no longer spawn the UI)
         * to a desktop notification. Pure, so it's unit-testable. No `&` in the Pango-bound text
         * (the panel/tooltip parse markup); the notify-send command is plain shell.
         */
        internal fun missingLauncherMarkup(body: String): String = buildPanelMarkup(
            body = "$body ⚠",
            color = WARN_COLOR,
            deltaText = null,
            tooltip = "Weather Widget: app files were removed — rebuild and restart " +
                "(scripts/buildStart-desktop.sh)",
            clickCmd = "notify-send -u critical -a 'Weather Widget' 'Weather Widget' " +
                "'App files were removed. Rebuild and restart: scripts/buildStart-desktop.sh'",
        )

        /**
         * Builds the genmon Pango markup. Pure (no I/O) so the delta logic is unit-testable.
         * When [deltaText] is non-null a second smaller orange span is appended next to the
         * temperature; the click handler MUST be <txtclick> (fires on the text, not an <img>).
         */
        internal fun buildPanelMarkup(
            body: String,
            color: String,
            deltaText: String?,
            tooltip: String,
            clickCmd: String,
        ): String {
            val deltaSpan = if (deltaText != null) {
                "<span font='Sans Bold 20' foreground='$DELTA_COLOR' line_height='0.6'> $deltaText</span>"
            } else ""
            return """
                <txt><span font='Sans Bold 22' foreground='$color' line_height='0.6'>$body</span>$deltaSpan</txt>
                <tool>$tooltip</tool>
                <txtclick>$clickCmd</txtclick>
            """.trimIndent()
        }

        private fun findGenmonPluginId(): String? {
            try {
                val listProcess = ProcessBuilder("xfconf-query", "-c", "xfce4-panel", "-l").start()
                val reader = listProcess.inputStream.bufferedReader()
                val lines = reader.readLines()
                listProcess.waitFor()

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/plugins/plugin-") && trimmed.endsWith("/command")) {
                        val queryProcess = ProcessBuilder("xfconf-query", "-c", "xfce4-panel", "-p", trimmed).start()
                        val valReader = queryProcess.inputStream.bufferedReader()
                        val cmdVal = valReader.readLine()?.trim() ?: ""
                        queryProcess.waitFor()

                        if (cmdVal.contains("genmon-weather-bin") || cmdVal.contains("genmon-weather.py")) {
                            val parts = trimmed.split("/")
                            if (parts.size >= 3) {
                                val pluginPart = parts[2] // "plugin-XX"
                                return pluginPart.substringAfter("plugin-")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up XFCE Genmon plugin ID: $e")
            }
            return null
        }
    }
}
