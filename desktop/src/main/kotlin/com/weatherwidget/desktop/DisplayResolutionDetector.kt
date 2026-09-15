package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Detects the active screen height for the desktop environment without initializing Java AWT
 * (which throws HeadlessException in the headless daemon and spawns unwanted X11 waker threads).
 *
 * Provides a pure resolution-to-font sizing function for XFCE genmon panel markup.
 */
object DisplayResolutionDetector {
    private const val TAG = "DisplayResolutionDetector"
    private const val CACHE_TTL_MS = 60_000L
    const val DEFAULT_FALLBACK_HEIGHT = 1080

    private var cachedHeight: Int? = null
    private var lastCheckMs = 0L

    fun currentScreenHeight(clockMs: Long = System.currentTimeMillis()): Int {
        val cached = cachedHeight
        if (cached != null && (clockMs - lastCheckMs) < CACHE_TTL_MS) {
            return cached
        }
        val detected = detectScreenHeight() ?: DEFAULT_FALLBACK_HEIGHT
        cachedHeight = detected
        lastCheckMs = clockMs
        return detected
    }

    /** Clears cache (useful for testing). */
    fun clearCache() {
        cachedHeight = null
        lastCheckMs = 0L
    }

    internal fun detectScreenHeight(): Int? {
        // 1. Try xrandr --current
        queryCommand("xrandr", "--current")?.let { output ->
            parseXrandrHeight(output)?.let { return it }
        }

        // 2. Try xwininfo -root
        queryCommand("xwininfo", "-root")?.let { output ->
            parseXwininfoHeight(output)?.let { return it }
        }

        // 3. Fallback: inspect sysfs DRM modes
        detectFromDrmSysfs()?.let { return it }

        return null
    }

    internal fun parseXrandrHeight(output: String): Int? {
        // Look for connected line: e.g. "HDMI-A-0 connected 1280x720+0+0" or "... connected primary 3840x2160+0+0"
        val connectedRegex = Regex("""\bconnected\s+(?:primary\s+)?(\d+)x(\d+)\+""")
        connectedRegex.find(output)?.let { match ->
            return match.groupValues[2].toIntOrNull()
        }

        // Alternative: "Screen 0: ... current 1280 x 720"
        val screenRegex = Regex("""\bcurrent\s+(\d+)\s*x\s*(\d+)""")
        screenRegex.find(output)?.let { match ->
            return match.groupValues[2].toIntOrNull()
        }

        return null
    }

    internal fun parseXwininfoHeight(output: String): Int? {
        val heightRegex = Regex("""\bHeight:\s*(\d+)""")
        return heightRegex.find(output)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun detectFromDrmSysfs(): Int? {
        return try {
            val drmDir = File("/sys/class/drm")
            if (!drmDir.isDirectory) return null
            val cardDirs = drmDir.listFiles { file -> file.isDirectory && file.name.contains("-") } ?: return null
            for (card in cardDirs) {
                val statusFile = File(card, "status")
                if (statusFile.exists() && statusFile.readText().trim() == "connected") {
                    val modesFile = File(card, "modes")
                    if (modesFile.exists()) {
                        val firstMode = modesFile.useLines { lines -> lines.firstOrNull() }
                        if (!firstMode.isNullOrBlank()) {
                            val parts = firstMode.trim().split("x")
                            if (parts.size == 2) {
                                parts[1].toIntOrNull()?.let { return it }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.v(TAG, "Failed reading DRM sysfs modes: $e")
            null
        }
    }

    private fun queryCommand(vararg command: String): String? {
        return try {
            val process = ProcessBuilder(*command).start()
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(500, TimeUnit.MILLISECONDS)
            if (exited && process.exitValue() == 0) output else null
        } catch (e: Exception) {
            Log.v(TAG, "Command failed (${command.joinToString(" ")}): $e")
            null
        }
    }

    /**
     * Resolves the genmon font sizes (Pair of tempFontSize to deltaFontSize) based on screen height.
     *
     * Scaling scale:
     * - 720p (H <= 720): 10pt temp / 9pt delta (~80px vertical stack in 26px panel, ~11% screen height)
     * - 1080p (H = 1080): 13pt temp / 12pt delta
     * - 1440p (H = 1440): 16pt temp / 15pt delta
     * - 2160p (4K, H >= 2160): 22pt temp / 20pt delta (original 4K baseline)
     */
    fun resolveGenmonFontSizes(screenHeight: Int): Pair<Int, Int> {
        val clamped = screenHeight.coerceIn(720, 2160)
        val tempSize = 10 + Math.round((clamped - 720) * (22.0 - 10.0) / (2160 - 720)).toInt()
        val deltaSize = 9 + Math.round((clamped - 720) * (20.0 - 9.0) / (2160 - 720)).toInt()
        return Pair(tempSize, deltaSize)
    }
}
