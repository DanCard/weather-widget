package com.weatherwidget.desktop

import com.weatherwidget.shared.util.SynopticBackoffStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed [SynopticBackoffStore]: the daemon and the UI are separate processes that both fetch
 * Synoptic, and a backoff one of them earned must hold for the other (Android keeps the same two
 * values in `synoptic_fetch_backoff` prefs). Format: `<failStreak> <backoffUntilMs>`. Best-effort —
 * an unreadable file reads as "no backoff", which at worst costs one extra request.
 */
class DesktopSynopticBackoffStore(private val file: Path) : SynopticBackoffStore {
    override fun failStreak(): Int = read().first

    override fun backoffUntilMs(): Long = read().second

    override fun save(failStreak: Int, backoffUntilMs: Long) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, "$failStreak $backoffUntilMs", StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun read(): Pair<Int, Long> = runCatching {
        val parts = Files.readString(file, StandardCharsets.UTF_8).trim().split(" ")
        (parts[0].toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0 to 0L)

    companion object {
        fun default(): DesktopSynopticBackoffStore =
            DesktopSynopticBackoffStore(appDataDir().resolve("synoptic_fetch_backoff"))
    }
}

/** Per-instance store for tests and any service built without a shared backoff file. */
class InMemorySynopticBackoffStore : SynopticBackoffStore {
    private var streak = 0
    private var until = 0L

    override fun failStreak() = streak

    override fun backoffUntilMs() = until

    override fun save(failStreak: Int, backoffUntilMs: Long) {
        streak = failStreak
        until = backoffUntilMs
    }
}
