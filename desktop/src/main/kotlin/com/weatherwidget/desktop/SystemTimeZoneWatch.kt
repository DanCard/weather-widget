package com.weatherwidget.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * Detects a system timezone change under a running JVM.
 *
 * The JVM resolves its default zone once and caches it for the life of the process, so after
 * `timedatectl set-timezone` every "today", day column, hour label and NOW marker stays in the old
 * zone until the app is relaunched (2026-09-30: both processes kept Europe/Warsaw, 9 h off, after the
 * system moved to America/Los_Angeles). The daemon polls this on its heartbeat and relaunches on a
 * change — see `plans/260930-desktop-restart-on-system-timezone-change.md`.
 */
object SystemTimeZoneWatch {
    val DEFAULT_LOCALTIME: Path = Path.of("/etc/localtime")
    val DEFAULT_TIMEZONE_FILE: Path = Path.of("/etc/timezone")

    private const val ZONEINFO = "zoneinfo/"

    /**
     * The system zone ID: the target of the [localtime] symlink after `zoneinfo/` (minus a `posix/`
     * or `right/` variant prefix), else the content of [timezoneFile], else null. Null means "can't
     * tell" (a copied, non-symlink localtime with no /etc/timezone), which callers treat as no change.
     */
    fun readSystemZoneId(
        localtime: Path = DEFAULT_LOCALTIME,
        timezoneFile: Path = DEFAULT_TIMEZONE_FILE,
    ): String? {
        val fromLink = runCatching {
            if (Files.isSymbolicLink(localtime)) zoneIdFromLinkTarget(Files.readSymbolicLink(localtime).toString()) else null
        }.getOrNull()
        if (fromLink != null) return fromLink
        return runCatching { Files.readString(timezoneFile).trim().takeIf { isValidZone(it) } }.getOrNull()
    }

    internal fun zoneIdFromLinkTarget(target: String): String? {
        val idx = target.lastIndexOf(ZONEINFO)
        if (idx < 0) return null
        val id = target.substring(idx + ZONEINFO.length).removePrefix("posix/").removePrefix("right/")
        return id.takeIf { isValidZone(it) }
    }

    /**
     * True when the app must relaunch: the system zone moved away from [baseline] (what this process
     * read at start) AND its rules differ from [jvmZone]. Comparing against the baseline rather than
     * the JVM's own ID is what keeps this from restart-looping when the two name the same zone
     * differently (`US/Pacific` vs `America/Los_Angeles`) — the successor reads a fresh baseline.
     */
    fun shouldRestartForZoneChange(baseline: String?, current: String?, jvmZone: ZoneId): Boolean {
        if (current == null || current == baseline) return false
        val currentZone = runCatching { ZoneId.of(current) }.getOrNull() ?: return false
        return currentZone.rules != jvmZone.rules
    }

    /** The JVM honours `TZ` over /etc/localtime, so watching the file is meaningless when it is set. */
    fun isWatchable(env: Map<String, String> = System.getenv()): Boolean = env["TZ"].isNullOrBlank()

    private fun isValidZone(id: String): Boolean =
        id.isNotEmpty() && runCatching { ZoneId.of(id) }.isSuccess
}
