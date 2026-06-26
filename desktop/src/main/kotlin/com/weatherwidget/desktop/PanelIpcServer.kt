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

        // The distributable can be deleted out from under us (a clean/rebuild) while this daemon
        // keeps running on its deleted inode. We still serve fresh data here, but the daemon can no
        // longer spawn the popup, so every genmon click fails silently. Surface that on the panel
        // itself (and rewire the click to explain the fix) instead of leaving the user guessing.
        if (isUiLauncherMissing()) {
            currentMarkup.set(missingLauncherMarkup(body))
            return
        }

        val isStale = dataStatus is DataStatus.Stale
        val color = if (isStale || temp == null) STALE_COLOR else LIVE_COLOR

        // Mirror the popup header (Main.kt): show the measured-vs-forecast offset, formatted "%+.1f"
        // (no degree symbol), only when it is non-trivial. Always orange, like the header.
        val deltaText = forecast?.appliedDelta
            ?.takeIf { kotlin.math.abs(it) >= 0.1f }
            ?.let { String.format(Locale.US, "%+.1f", it) }

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

        currentMarkup.set(buildPanelMarkup(body, color, deltaText, tooltip, clickCmd))
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
                "(scripts/buildStart.sh)",
            clickCmd = "notify-send -u critical -a 'Weather Widget' 'Weather Widget' " +
                "'App files were removed. Rebuild and restart: scripts/buildStart.sh'",
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
    }
}
