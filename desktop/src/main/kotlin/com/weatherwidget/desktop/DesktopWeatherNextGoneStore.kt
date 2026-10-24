package com.weatherwidget.desktop

import com.weatherwidget.shared.rainmap.WeatherNextLoop
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed [WeatherNextLoop.GoneStore]: when the WeatherNext host last answered 404/410, and
 * which. It was in memory, so every restart (daily, from autostart) asked the gone host again;
 * Android keeps the same two values in `weathernext_prefs`. Format: `<goneAtMs> <status>`.
 * Best-effort — an unreadable file reads as "not gone", which at worst costs one request.
 */
class DesktopWeatherNextGoneStore(private val file: Path) : WeatherNextLoop.GoneStore {
    override val goneAtMs: Long? get() = read()?.first

    override val goneStatus: Int get() = read()?.second ?: 404

    override fun markGone(status: Int, nowMs: Long) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, "$nowMs $status", StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(file) }
    }

    private fun read(): Pair<Long, Int>? = runCatching {
        val parts = Files.readString(file, StandardCharsets.UTF_8).trim().split(" ")
        val at = parts[0].toLongOrNull() ?: return null
        at to (parts.getOrNull(1)?.toIntOrNull() ?: 404)
    }.getOrNull()

    companion object {
        fun default(): DesktopWeatherNextGoneStore =
            DesktopWeatherNextGoneStore(appDataDir().resolve("weathernext_gone"))
    }
}
